package com.mavinci.yoloseg.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.mavinci.yoloseg.ModelManager;
import com.mavinci.yoloseg.R;
import com.mavinci.yoloseg.YoloSegJni;
import com.mavinci.yoloseg.util.ImageSaver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Video mode — pick a video file, extract frames, run segmentation,
 * display frame-by-frame on a seek bar with FPS counter.
 *
 * Strategy: pre-process ALL frames off-thread, store processed bitmaps,
 * let user scrub through with SeekBar.  For very long videos only the
 * currently-displayed ±5 frames are kept in memory to avoid OOM.
 */
public class VideoActivity extends AppCompatActivity {

    private ImageView   ivFrame;
    private SeekBar     seekFrame;
    private Button      btnPick, btnSave;
    private TextView    tvStats, tvFrameInfo;
    private ProgressBar progressBar;

    private float confThresh, nmsThresh, maskAlpha;
    private int   modelIndex;
    private boolean useVulkan;

    // Frame ring-buffer (keep at most MAX_CACHED processed frames in RAM)
    private static final int MAX_CACHED = 60;
    private Bitmap[] cachedFrames;
    private int      totalFrames = 0;
    private int      currentFrame = 0;
    private Uri      videoUri = null;

    private final ExecutorService executor  = Executors.newSingleThreadExecutor();
    private final AtomicBoolean   cancelled = new AtomicBoolean(false);

    private final ActivityResultLauncher<String> pickerLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) loadVideo(uri);
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video);

        ivFrame     = findViewById(R.id.iv_video_frame);
        seekFrame   = findViewById(R.id.seek_video_frame);
        btnPick     = findViewById(R.id.btn_pick_video);
        btnSave     = findViewById(R.id.btn_save_video_frame);
        tvStats     = findViewById(R.id.tv_video_stats);
        tvFrameInfo = findViewById(R.id.tv_frame_info);
        progressBar = findViewById(R.id.progress_video);

        Intent intent = getIntent();
        modelIndex = intent.getIntExtra("model_index", 0);
        useVulkan  = intent.getBooleanExtra("use_vulkan", false);
        confThresh = intent.getFloatExtra("conf_thresh", 0.25f);
        nmsThresh  = intent.getFloatExtra("nms_thresh",  0.45f);
        maskAlpha  = intent.getFloatExtra("mask_alpha",  0.50f);

        loadModel();

        btnPick.setOnClickListener(v -> pickerLauncher.launch("video/*"));
        btnSave.setOnClickListener(v -> saveCurrentFrame());
        btnSave.setEnabled(false);

        seekFrame.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                if (fromUser && cachedFrames != null && cachedFrames[prog] != null) {
                    currentFrame = prog;
                    ivFrame.setImageBitmap(cachedFrames[prog]);
                    tvFrameInfo.setText("Frame: " + (prog + 1) + " / " + totalFrames);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void loadModel() {
        executor.execute(() -> {
            String[] paths = ModelManager.getModelPaths(getAssets(), modelIndex);
            if (paths == null) return;
            YoloSegJni.loadModel(getAssets(), paths[0], paths[1], useVulkan);
        });
    }

    @SuppressLint("SetTextI18n")
    private void loadVideo(Uri uri) {
        if (!YoloSegJni.isModelLoaded()) {
            Toast.makeText(this, "Model henüz yüklenmedi!", Toast.LENGTH_SHORT).show();
            return;
        }
        cancelled.set(false);
        videoUri = uri;
        btnPick.setEnabled(false);
        btnSave.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStats.setText("Video yükleniyor...");

        executor.execute(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, uri);
                String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0;

                // Extract frames at ~10 fps (or fewer for long videos)
                int fps = 10;
                totalFrames = (int)(durationMs / 1000.0 * fps);
                totalFrames = Math.min(totalFrames, MAX_CACHED);  // cap
                cachedFrames = new Bitmap[totalFrames];

                runOnUiThread(() -> {
                    seekFrame.setMax(Math.max(0, totalFrames - 1));
                    progressBar.setMax(totalFrames);
                });

                long frameIntervalUs = (long)(1_000_000.0 / fps);

                for (int i = 0; i < totalFrames; i++) {
                    if (cancelled.get()) break;

                    long timeUs = i * frameIntervalUs;
                    Bitmap raw = retriever.getFrameAtTime(timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (raw == null) continue;

                    Bitmap argb = raw.copy(Bitmap.Config.ARGB_8888, false);
                    raw.recycle();

                    long t0 = SystemClock.elapsedRealtime();
                    Bitmap result = YoloSegJni.segment(argb, confThresh, nmsThresh, maskAlpha);
                    long ms = SystemClock.elapsedRealtime() - t0;
                    final String summary = YoloSegJni.getLastDetectionSummary();
                    argb.recycle();

                    final int idx = i;
                    final long infer_ms = ms;
                    cachedFrames[i] = result;

                    runOnUiThread(() -> {
                        progressBar.setProgress(idx + 1);
                        ivFrame.setImageBitmap(result);
                        tvFrameInfo.setText("Frame: " + (idx + 1) + " / " + totalFrames);
                        tvStats.setText("Inference: " + infer_ms + " ms | " + summary);
                        seekFrame.setProgress(idx);
                        btnSave.setEnabled(true);
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() ->
                    Toast.makeText(this, "Video işlenemedi: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnPick.setEnabled(true);
                });
            }
        });
    }

    private void saveCurrentFrame() {
        if (cachedFrames == null || cachedFrames[currentFrame] == null) return;
        Bitmap bmp = cachedFrames[currentFrame];
        executor.execute(() -> {
            String path = ImageSaver.saveBitmap(this, bmp, "yoloseg_video_frame_" + currentFrame);
            runOnUiThread(() -> {
                if (path != null) Toast.makeText(this, "Kaydedildi: " + path, Toast.LENGTH_LONG).show();
                else Toast.makeText(this, "Kaydetme başarısız!", Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    protected void onDestroy() {
        cancelled.set(true);
        executor.shutdown();
        super.onDestroy();
    }
}
