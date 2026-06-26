#pragma once

// Minimal NDK Camera2 wrapper for real-time camera frame capture.
// Delivers frames via callback in YUV_420_888 format.

#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraCaptureSession.h>
#include <camera/NdkCameraMetadataTags.h>
#include <media/NdkImageReader.h>
#include <android/log.h>
#include <functional>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>

/**
 * Callback type: called on each captured frame.
 * yuvData    — raw YUV_420_888 bytes
 * width/height — image dimensions
 * rowStride  — Y-plane row stride in bytes
 */
using FrameCallback = std::function<void(const uint8_t* yuvData,
                                         int width, int height,
                                         int yStride, int uvStride,
                                         int uvPixelStride)>;

class NdkCamera {
public:
    NdkCamera();
    ~NdkCamera();

    /**
     * Open the back-facing camera and start streaming frames.
     * @param width   Requested frame width  (best-match chosen)
     * @param height  Requested frame height
     * @param cb      Called on every new frame from the camera thread
     * @return 0 on success
     */
    int  open(int width, int height, FrameCallback cb);

    /** Stop capture and release all camera resources. */
    void close();

    bool is_open() const { return m_open.load(); }

private:
    // ── Camera handles ────────────────────────────────────────
    ACameraManager*         m_camera_mgr    = nullptr;
    ACameraDevice*          m_camera_dev    = nullptr;
    ACaptureSessionOutput*  m_session_out   = nullptr;
    ACaptureSessionOutputContainer* m_out_container = nullptr;
    ACameraCaptureSession*  m_session       = nullptr;
    ACaptureRequest*        m_request       = nullptr;
    ACameraOutputTarget*    m_out_target    = nullptr;

    // ── Image reader ──────────────────────────────────────────
    AImageReader*           m_reader        = nullptr;

    // ── State ─────────────────────────────────────────────────
    std::atomic<bool>       m_open{false};
    FrameCallback           m_callback;

    // ── Camera device callbacks ───────────────────────────────
    static void onDeviceDisconnected(void* ctx, ACameraDevice* dev);
    static void onDeviceError(void* ctx, ACameraDevice* dev, int err);
    ACameraDevice_StateCallbacks m_dev_cbs{};

    // ── Session callbacks ─────────────────────────────────────
    static void onSessionReady(void* ctx, ACameraCaptureSession* s);
    static void onSessionClosed(void* ctx, ACameraCaptureSession* s);
    static void onSessionActive(void* ctx, ACameraCaptureSession* s);
    ACameraCaptureSession_stateCallbacks m_ses_cbs{};

    // ── Image available callback ──────────────────────────────
    static void onImageAvailable(void* ctx, AImageReader* reader);

    // ── Helper: pick best camera id (back-facing) ─────────────
    std::string select_camera_id();
};
