package com.mavinci.yoloseg.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.mavinci.yoloseg.ModelManager;
import com.mavinci.yoloseg.R;
import com.mavinci.yoloseg.YoloSegJni;
import com.mavinci.yoloseg.util.ImageSaver;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Camera mode — real-time segmentation via CameraX + ImageAnalysis.
 *
 * Architecture:
 *  CameraX → ImageAnalysis → YUV→JPEG→Bitmap → JNI segment() → ImageView overlay
 *
 * The preview is shown via PreviewView; the segmented result is shown as a
 * translucent ImageView layered on top.
 */
public class CameraActivity extends AppCompatActivity {

    private PreviewView previewView;
    private ImageView   ivResult;
    private TextView    tvFps, tvDetections;
    private Button      btnSave;

    private float confThresh, nmsThresh, maskAlpha;
    private int   modelIndex;
    private boolean useVulkan;

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean   processing       = new AtomicBoolean(false);

    // FPS tracking
    private long  lastFrameTimeMs = 0;
    private float fps             = 0f;

    // Latest result bitmap for save
    private volatile Bitmap latestResult = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        previewView  = findViewById(R.id.preview_view);
        ivResult     = findViewById(R.id.iv_result_overlay);
        tvFps        = findViewById(R.id.tv_fps);
        tvDetections = findViewById(R.id.tv_detections);
        btnSave      = findViewById(R.id.btn_save_camera);

        Intent intent = getIntent();
        modelIndex = intent.getIntExtra("model_index", 0);
        useVulkan  = intent.getBooleanExtra("use_vulkan", false);
        confThresh = intent.getFloatExtra("conf_thresh", 0.25f);
        nmsThresh  = intent.getFloatExtra("nms_thresh",  0.45f);
        maskAlpha  = intent.getFloatExtra("mask_alpha",  0.50f);

        btnSave.setOnClickListener(v -> saveLatestFrame());

        loadModelThenStart();
    }

    private void loadModelThenStart() {
        analysisExecutor.execute(() -> {
            String[] paths = ModelManager.getModelPaths(getAssets(), modelIndex);
            if (paths == null) {
                runOnUiThread(() ->
                    Toast.makeText(this, "Model assets bulunamadı!", Toast.LENGTH_LONG).show());
                return;
            }
            boolean ok = YoloSegJni.loadModel(getAssets(), paths[0], paths[1], useVulkan);
            if (!ok) {
                runOnUiThread(() ->
                    Toast.makeText(this, "Model yüklenemedi!", Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(this::startCamera);
        });
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                // Preview (shows live camera)
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // ImageAnalysis (delivers frames for inference)
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new android.util.Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build();

                analysis.setAnalyzer(analysisExecutor, this::analyzeFrame);

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;

                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview, analysis);

            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Kamera başlatılamadı: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyzeFrame(@NonNull ImageProxy image) {
        // Drop frames if still processing previous one
        if (!processing.compareAndSet(false, true)) {
            image.close();
            return;
        }

        try {
            long t0 = SystemClock.elapsedRealtime();

            // Convert YUV_420_888 → NV21 byte array
            byte[] nv21 = yuv420ToNv21(image);
            int w = image.getWidth();
            int h = image.getHeight();

            // Convert NV21 → JPEG → Bitmap  (simpler and handles rotation)
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, w, h), 90, baos);
            byte[] jpegBytes = baos.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

            if (bitmap == null) { image.close(); processing.set(false); return; }

            Bitmap argb = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            bitmap.recycle();

            Bitmap result = YoloSegJni.segment(argb, confThresh, nmsThresh, maskAlpha);
            argb.recycle();

            long ms = SystemClock.elapsedRealtime() - t0;

            // FPS calculation
            long now = SystemClock.elapsedRealtime();
            if (lastFrameTimeMs > 0) {
                float interval = (now - lastFrameTimeMs) / 1000.0f;
                fps = 1.0f / interval;
            }
            lastFrameTimeMs = now;
            final float displayFps = fps;

            latestResult = result;
            final String summary = YoloSegJni.getLastDetectionSummary();

            runOnUiThread(() -> {
                if (result != null) {
                    ivResult.setImageBitmap(result);
                }
                tvFps.setText(String.format("%.1f FPS  |  %d ms", displayFps, ms));
                tvDetections.setText(summary);
            });

        } finally {
            image.close();
            processing.set(false);
        }
    }

    /**
     * Convert ImageProxy YUV_420_888 to NV21 byte array.
     * Handles both planar (pixel stride=1) and semi-planar (pixel stride=2) UV planes.
     */
    private byte[] yuv420ToNv21(@NonNull ImageProxy image) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] nv21 = new byte[w * h * 3 / 2];

        ImageProxy.PlaneProxy yPlane  = image.getPlanes()[0];
        ImageProxy.PlaneProxy uPlane  = image.getPlanes()[1];
        ImageProxy.PlaneProxy vPlane  = image.getPlanes()[2];

        ByteBuffer yBuf  = yPlane.getBuffer();
        ByteBuffer uBuf  = uPlane.getBuffer();
        ByteBuffer vBuf  = vPlane.getBuffer();

        int yStride  = yPlane.getRowStride();
        int uvStride = uPlane.getRowStride();
        int uvPixStride = uPlane.getPixelStride();

        // Copy Y plane
        for (int row = 0; row < h; row++) {
            yBuf.position(row * yStride);
            yBuf.get(nv21, row * w, w);
        }

        // Copy VU (NV21 order) interleaved
        int uvOffset = w * h;
        int vStride = vPlane.getRowStride();
        int vPixStride = vPlane.getPixelStride();
        int uStride = uPlane.getRowStride();
        int uPixStride = uPlane.getPixelStride();

        for (int row = 0; row < h / 2; row++) {
            for (int col = 0; col < w / 2; col++) {
                int vIdx = row * vStride + col * vPixStride;
                int uIdx = row * uStride + col * uPixStride;
                nv21[uvOffset + row * w + col * 2]     = vBuf.get(vIdx); // V
                nv21[uvOffset + row * w + col * 2 + 1] = uBuf.get(uIdx); // U
            }
        }
        return nv21;
    }

    private void saveLatestFrame() {
        Bitmap bmp = latestResult;
        if (bmp == null) return;
        analysisExecutor.execute(() -> {
            String path = ImageSaver.saveBitmap(this, bmp, "yoloseg_camera_" + System.currentTimeMillis());
            runOnUiThread(() -> {
                if (path != null) Toast.makeText(this, "Kaydedildi: " + path, Toast.LENGTH_LONG).show();
                else Toast.makeText(this, "Kaydetme başarısız!", Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    protected void onDestroy() {
        analysisExecutor.shutdown();
        super.onDestroy();
    }
}
