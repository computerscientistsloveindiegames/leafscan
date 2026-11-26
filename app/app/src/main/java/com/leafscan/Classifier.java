package com.leafscan;

import android.content.Context;
import android.graphics.Bitmap;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Roda um modelo .tflite exportado pelo notebook 05.
 * Entrada: imagem RGB 128x128, float32, pixels 0..255 (a normalização está dentro do modelo).
 * Saída: 38 probabilidades (softmax), na ordem do labels.txt.
 */
public class Classifier implements Closeable {
    public static final int IMG = 128;

    private final Interpreter interpreter;
    private final ByteBuffer input;
    private final float[][] output;
    private final int[] pixels = new int[IMG * IMG];
    public final long modelBytes;

    public Classifier(Context ctx, String assetName, int numClasses, int threads) throws IOException {
        ByteBuffer model = loadAsset(ctx, assetName);
        modelBytes = model.capacity();
        Interpreter.Options opts = new Interpreter.Options();
        opts.setNumThreads(threads);
        interpreter = new Interpreter(model, opts);
        interpreter.resizeInput(0, new int[]{1, IMG, IMG, 3});
        interpreter.allocateTensors();
        input = ByteBuffer.allocateDirect(4 * IMG * IMG * 3).order(ByteOrder.nativeOrder());
        output = new float[1][numClasses];
    }

    private static ByteBuffer loadAsset(Context ctx, String name) throws IOException {
        try (InputStream is = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] bytes = bos.toByteArray();
            ByteBuffer bb = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
            bb.put(bytes);
            bb.rewind();
            return bb;
        }
    }

    private void fillInput(Bitmap bmp) {
        bmp.getPixels(pixels, 0, IMG, 0, 0, IMG, IMG);
        input.rewind();
        for (int p : pixels) {
            input.putFloat((p >> 16) & 0xFF);   // R
            input.putFloat((p >> 8) & 0xFF);    // G
            input.putFloat(p & 0xFF);           // B
        }
        input.rewind();
    }

    /** Classifica um bitmap 128x128. Retorna as probabilidades. */
    public synchronized float[] classify(Bitmap bmp128) {
        fillInput(bmp128);
        interpreter.run(input, output);
        return output[0].clone();
    }

    /** Mede a latência só da inferência. Retorna {média, mediana} em ms. */
    public synchronized double[] benchmark(Bitmap bmp128, int warmup, int runs) {
        fillInput(bmp128);
        for (int i = 0; i < warmup; i++) {
            input.rewind();
            interpreter.run(input, output);
        }
        double[] t = new double[runs];
        for (int i = 0; i < runs; i++) {
            input.rewind();
            long t0 = System.nanoTime();
            interpreter.run(input, output);
            t[i] = (System.nanoTime() - t0) / 1e6;
        }
        double mean = 0;
        for (double v : t) mean += v;
        mean /= runs;
        Arrays.sort(t);
        double median = runs % 2 == 1 ? t[runs / 2] : (t[runs / 2 - 1] + t[runs / 2]) / 2;
        return new double[]{mean, median};
    }

    @Override
    public synchronized void close() {
        interpreter.close();
    }
}
