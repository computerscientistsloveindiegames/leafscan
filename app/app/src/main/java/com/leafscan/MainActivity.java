package com.leafscan;

import android.content.ActivityNotFoundException;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    /** Abaixo desse valor de confiança o app pede outra foto (ver notebooks 03/04). */
    private static final float THRESHOLD = 0.70f;
    private static final int THREADS = 4;

    /** {nome exibido, arquivo em models/}. O primeiro é o padrão. */
    private static final String[][] MODELS = {
            {"CNN própria — int8 (recomendado)", "cnn_base_int8.tflite"},
            {"CNN própria — float32", "cnn_base_fp32.tflite"},
            {"MobileNetV3-Small — int8", "mnv3s_int8.tflite"},
            {"MobileNetV3-Small — float32", "mnv3s_fp32.tflite"},
            {"MobileNetV2 (α=0.5) — int8", "mnv2_050_int8.tflite"},
            {"MobileNetV2 (α=0.5) — float32", "mnv2_050_fp32.tflite"},
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Classifier classifier;
    private volatile Bitmap input128;
    private volatile int modelIndex = 0;
    private Labels labels;
    private Uri photoUri;

    private ImageView imagePreview;
    private TextView textPlaceholder, textStatus, textBenchmark, textWarning;
    private View resultsBox;
    private TextView[] rowLabels;
    private LinearProgressIndicator[] rowBars;

    private ActivityResultLauncher<Uri> takePicture;
    private ActivityResultLauncher<String> pickImage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imagePreview = findViewById(R.id.imagePreview);
        textPlaceholder = findViewById(R.id.textPlaceholder);
        textStatus = findViewById(R.id.textStatus);
        textBenchmark = findViewById(R.id.textBenchmark);
        textWarning = findViewById(R.id.textWarning);
        resultsBox = findViewById(R.id.resultsBox);
        rowLabels = new TextView[]{findViewById(R.id.label1), findViewById(R.id.label2), findViewById(R.id.label3)};
        rowBars = new LinearProgressIndicator[]{findViewById(R.id.bar1), findViewById(R.id.bar2), findViewById(R.id.bar3)};

        try {
            labels = new Labels(this);
        } catch (Exception e) {
            textStatus.setText("Erro: labels.txt não encontrado na pasta models/.");
            return;
        }

        takePicture = registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
            if (Boolean.TRUE.equals(ok) && photoUri != null) handleImage(photoUri);
        });
        pickImage = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) handleImage(uri);
        });

        findViewById(R.id.btnCamera).setOnClickListener(v -> openCamera());
        findViewById(R.id.btnGallery).setOnClickListener(v -> pickImage.launch("image/*"));
        findViewById(R.id.btnBenchmark).setOnClickListener(v -> benchmark());

        Spinner spinner = findViewById(R.id.spinnerModel);
        String[] names = new String[MODELS.length];
        for (int i = 0; i < MODELS.length; i++) names[i] = MODELS[i][0];
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                loadModel(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void openCamera() {
        try {
            File dir = new File(getCacheDir(), "photos");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("cache");
            File f = new File(dir, "foto.jpg");
            photoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            takePicture.launch(photoUri);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_camera, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Erro ao abrir a câmera: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadModel(int index) {
        modelIndex = index;
        textStatus.setText("Carregando " + MODELS[index][0] + "…");
        textBenchmark.setText("");
        worker.execute(() -> {
            Classifier old = classifier;
            classifier = null;
            if (old != null) old.close();
            try {
                long t0 = System.nanoTime();
                Classifier c = new Classifier(this, MODELS[index][1], labels.size(), THREADS);
                double ms = (System.nanoTime() - t0) / 1e6;
                classifier = c;
                String msg = String.format(Locale.US, "Modelo: %s (%.2f MB, carregado em %.0f ms)",
                        MODELS[index][1], c.modelBytes / 1e6, ms);
                runOnUiThread(() -> textStatus.setText(msg));
                if (input128 != null) classifyNow();
            } catch (Exception e) {
                runOnUiThread(() -> textStatus.setText("Erro ao carregar " + MODELS[index][1]
                        + ". Confira se o arquivo está em models/. (" + e.getMessage() + ")"));
            }
        });
    }

    private void handleImage(Uri uri) {
        worker.execute(() -> {
            try {
                Bitmap full = ImageUtils.load(this, uri, 512);
                Bitmap square = ImageUtils.centerSquare(full);
                input128 = ImageUtils.resizeSquare(square, Classifier.IMG);
                runOnUiThread(() -> {
                    imagePreview.setImageBitmap(square);
                    textPlaceholder.setVisibility(View.GONE);
                });
                classifyNow();
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Erro ao ler a imagem: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Roda na thread de trabalho. */
    private void classifyNow() {
        Classifier c = classifier;
        Bitmap img = input128;
        if (c == null || img == null) return;
        long t0 = System.nanoTime();
        float[] p = c.classify(img);
        double ms = (System.nanoTime() - t0) / 1e6;
        int[] top = top3(p);
        runOnUiThread(() -> showResult(p, top, ms));
    }

    private void showResult(float[] p, int[] top, double ms) {
        resultsBox.setVisibility(View.VISIBLE);
        for (int k = 0; k < 3; k++) {
            int i = top[k];
            rowLabels[k].setText(String.format(Locale.US, "%s  —  %.1f%%", labels.display(i), 100 * p[i]));
            rowBars[k].setProgress(Math.round(100 * p[i]));
        }
        textWarning.setVisibility(p[top[0]] < THRESHOLD ? View.VISIBLE : View.GONE);
        textBenchmark.setText(String.format(Locale.US, "Inferência desta foto: %.1f ms (%s, %d threads)",
                ms, MODELS[modelIndex][1], THREADS));
    }

    private static int[] top3(float[] p) {
        int[] top = {-1, -1, -1};
        for (int i = 0; i < p.length; i++) {
            for (int k = 0; k < 3; k++) {
                if (top[k] == -1 || p[i] > p[top[k]]) {
                    for (int j = 2; j > k; j--) top[j] = top[j - 1];
                    top[k] = i;
                    break;
                }
            }
        }
        return top;
    }

    private void benchmark() {
        if (input128 == null) {
            Toast.makeText(this, R.string.need_image, Toast.LENGTH_SHORT).show();
            return;
        }
        textBenchmark.setText("Medindo…");
        worker.execute(() -> {
            Classifier c = classifier;
            if (c == null) return;
            double[] r = c.benchmark(input128, 10, 50);
            String msg = String.format(Locale.US,
                    "Latência (%s, %d threads, 50 execuções): média %.2f ms | mediana %.2f ms",
                    MODELS[modelIndex][1], THREADS, r[0], r[1]);
            runOnUiThread(() -> textBenchmark.setText(msg));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.execute(() -> {
            Classifier c = classifier;
            classifier = null;
            if (c != null) c.close();
        });
        worker.shutdown();
    }
}
