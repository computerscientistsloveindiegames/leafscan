package com.leafscan;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import java.io.IOException;
import java.io.InputStream;

/** Mesmo pré-processamento do notebook 01: corrige rotação EXIF, recorta o quadrado central e reduz. */
public final class ImageUtils {
    private ImageUtils() {}

    /** Lê a imagem já reduzida (lado menor >= minSide) e corrigida pela orientação EXIF. */
    public static Bitmap load(Context ctx, Uri uri, int minSide) throws IOException {
        ContentResolver cr = ctx.getContentResolver();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Imagem inválida");

        int sample = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= minSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        try (InputStream is = cr.openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(is, null, opts);
        }
        if (bmp == null) throw new IOException("Não foi possível ler a imagem");

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream is = cr.openInputStream(uri)) {
            if (is != null) {
                orientation = new ExifInterface(is).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (IOException ignored) {
        }
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.postRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(270); m.postScale(-1, 1); break;
            default: return bmp;
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
    }

    /** Recorte quadrado central. */
    public static Bitmap centerSquare(Bitmap b) {
        int s = Math.min(b.getWidth(), b.getHeight());
        return Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
    }

    /**
     * Reduz para size x size em etapas de 2x (bilinear), para aproximar a redução com antialiasing
     * usada no treino (PIL) e evitar serrilhado.
     */
    public static Bitmap resizeSquare(Bitmap square, int size) {
        Bitmap cur = square;
        while (cur.getWidth() / 2 >= size) {
            cur = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
        }
        if (cur.getWidth() != size || cur.getHeight() != size) {
            cur = Bitmap.createScaledBitmap(cur, size, size, true);
        }
        return cur.getConfig() == Bitmap.Config.ARGB_8888 ? cur : cur.copy(Bitmap.Config.ARGB_8888, false);
    }
}
