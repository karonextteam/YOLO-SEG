package com.mavinci.yoloseg.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.mavinci.yoloseg.ModelManager;
import com.mavinci.yoloseg.R;
import com.mavinci.yoloseg.YoloSegJni;
import com.mavinci.yoloseg.util.ImageSaver;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Image mode — pick from gallery, run segmentation, show result, optionally save.
 */
public class ImageActivity extends AppCompatActivity {

    private ImageView   imageView;
    private Button      btnPick, btnSave;
    private TextView    tvStats;
    private ProgressBar progress;

    private Bitmap resultBitmap = null;
    private float confThresh, nmsThresh, maskAlpha;
    private int   modelIndex;
    private boolean useVulkan;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> pickerLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) processImage(uri);
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image);

        imageView  = findViewById(R.id.image_view);
        btnPick    = findViewById(R.id.btn_pick_image);
        btnSave    = findViewById(R.id.btn_save_image);
        tvStats    = findViewById(R.id.tv_stats);
        progress   = findViewById(R.id.progress_bar);

        // Unpack settings from MainActivity
        Intent intent = getIntent();
        modelIndex = intent.getIntExtra("model_index", 0);
        useVulkan  = intent.getBooleanExtra("use_vulkan", false);
        confThresh = intent.getFloatExtra("conf_thresh", 0.25f);
        nmsThresh  = intent.getFloatExtra("nms_thresh",  0.45f);
        maskAlpha  = intent.getFloatExtra("mask_alpha",  0.50f);

        loadModel();

        btnPick.setOnClickListener(v -> pickerLauncher.launch("image/*"));
        btnSave.setOnClickListener(v -> saveResult());
        btnSave.setEnabled(false);
    }

    private void loadModel() {
        executor.execute(() -> {
            String[] paths = ModelManager.getModelPaths(getAssets(), modelIndex);
            if (paths == null) {
                runOnUiThread(() ->
                    Toast.makeText(this, "Model bulunamadı!", Toast.LENGTH_LONG).show());
                return;
            }
            boolean ok = YoloSegJni.loadModel(getAssets(), paths[0], paths[1], useVulkan);
            if (!ok) {
                runOnUiThread(() ->
                    Toast.makeText(this, "Model yüklenemedi!", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void processImage(Uri uri) {
        if (!YoloSegJni.isModelLoaded()) {
            Toast.makeText(this, "Model henüz yüklenmedi, lütfen bekleyin.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Load bitmap from URI
        Bitmap bitmap;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            Toast.makeText(this, "Resim açılamadı: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        if (bitmap == null) {
            Toast.makeText(this, "Geçersiz resim.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Ensure ARGB_8888 (JNI expects this format)
        final Bitmap argb = bitmap.copy(Bitmap.Config.ARGB_8888, false);

        progress.setVisibility(View.VISIBLE);
        btnPick.setEnabled(false);
        btnSave.setEnabled(false);
        imageView.setImageDrawable(null);

        executor.execute(() -> {
            long t0 = SystemClock.elapsedRealtime();
            Bitmap result = YoloSegJni.segment(argb, confThresh, nmsThresh, maskAlpha);
            long ms = SystemClock.elapsedRealtime() - t0;
            final String summary = YoloSegJni.getLastDetectionSummary();
            argb.recycle();

            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                btnPick.setEnabled(true);
                if (result != null) {
                    resultBitmap = result;
                    imageView.setImageBitmap(result);
                    tvStats.setText(String.format("Inference: %d ms | %s", ms, summary));
                    btnSave.setEnabled(true);
                } else {
                    Toast.makeText(this, "Segmentasyon başarısız.", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void saveResult() {
        if (resultBitmap == null) return;
        executor.execute(() -> {
            String path = ImageSaver.saveBitmap(this, resultBitmap, "yoloseg_result");
            runOnUiThread(() -> {
                if (path != null)
                    Toast.makeText(this, "Kaydedildi: " + path, Toast.LENGTH_LONG).show();
                else
                    Toast.makeText(this, "Kaydetme başarısız!", Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
