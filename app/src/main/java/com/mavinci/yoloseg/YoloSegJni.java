package com.mavinci.yoloseg;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

/**
 * JNI proxy class — all native methods implemented in yoloseg_jni.cpp.
 * Call loadModel() once before any segment*() call.
 */
public class YoloSegJni {

    static {
        System.loadLibrary("yoloseg");
    }

    // ── Model loading ─────────────────────────────────────────────────────

    /**
     * Load NCNN model from app assets.
     *
     * @param mgr        Android AssetManager (pass getAssets())
     * @param paramPath  e.g. "yolo11n-seg.ncnn.param"
     * @param binPath    e.g. "yolo11n-seg.ncnn.bin"
     * @param useVulkan  true = GPU (Vulkan), false = CPU
     * @return true on success
     */
    public static native boolean loadModel(AssetManager mgr,
                                           String paramPath,
                                           String binPath,
                                           boolean useVulkan);

    /** @return true if model is loaded and ready */
    public static native boolean isModelLoaded();

    // ── Inference ─────────────────────────────────────────────────────────

    /**
     * Run segmentation on a Bitmap.
     * The result Bitmap is a new ARGB_8888 image with masks drawn on it.
     *
     * @param input      ARGB_8888 Bitmap (any size; will be letterboxed internally)
     * @param confThresh Confidence threshold 0.0–1.0 (default 0.25)
     * @param nmsThresh  NMS IoU threshold   0.0–1.0 (default 0.45)
     * @param alpha      Mask opacity        0.0–1.0 (default 0.5)
     * @return Result Bitmap with masks drawn, or null on error
     */
    public static native Bitmap segment(Bitmap input,
                                        float confThresh,
                                        float nmsThresh,
                                        float alpha);

    /** @return Number of objects detected in the last segment() call */
    public static native int getLastDetectionCount();

    /** @return Summary string of last detections (e.g. "2 person, 1 car") */
    public static native String getLastDetectionSummary();

    /**
     * Run segmentation on a raw YUV_420_888 frame (from CameraX ImageProxy).
     *
     * @param yuvData        Byte array — Y plane bytes (width×height) followed by UV bytes
     * @param width          Frame width
     * @param height         Frame height
     * @param yStride        Y-plane row stride
     * @param uvStride       UV-plane row stride
     * @param uvPixelStride  UV-plane pixel stride (2 for NV21/NV12, 1 for I420)
     * @param confThresh     Confidence threshold
     * @param nmsThresh      NMS threshold
     * @param alpha          Mask opacity
     * @return Result Bitmap with masks, or null on error
     */
    public static native Bitmap segmentYuvFrame(byte[] yuvData,
                                                int width,
                                                int height,
                                                int yStride,
                                                int uvStride,
                                                int uvPixelStride,
                                                float confThresh,
                                                float nmsThresh,
                                                float alpha);
}
