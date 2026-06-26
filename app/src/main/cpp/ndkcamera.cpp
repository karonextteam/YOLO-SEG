#include "ndkcamera.h"
#include <android/log.h>
#include <cstring>

#define TAG  "NdkCamera"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── Constructor / Destructor ──────────────────────────────────────────────
NdkCamera::NdkCamera() = default;

NdkCamera::~NdkCamera() {
    close();
}

// ─── select_camera_id ─────────────────────────────────────────────────────
std::string NdkCamera::select_camera_id() {
    ACameraIdList* id_list = nullptr;
    ACameraManager_getCameraIdList(m_camera_mgr, &id_list);

    std::string selected;
    for (int i = 0; i < id_list->numCameras; ++i) {
        const char* id = id_list->cameraIds[i];
        ACameraMetadata* meta = nullptr;
        ACameraManager_getCameraCharacteristics(m_camera_mgr, id, &meta);

        ACameraMetadata_const_entry lens_entry{};
        ACameraMetadata_getConstEntry(meta, ACAMERA_LENS_FACING, &lens_entry);
        auto facing = static_cast<acamera_metadata_enum_android_lens_facing_t>(
            lens_entry.data.u8[0]);

        ACameraMetadata_free(meta);

        if (facing == ACAMERA_LENS_FACING_BACK) {
            selected = id;
            break;
        }
    }
    ACameraManager_deleteCameraIdList(id_list);

    if (selected.empty() && id_list->numCameras > 0)
        selected = id_list->cameraIds[0]; // fallback to first

    return selected;
}

// ─── Device callbacks ─────────────────────────────────────────────────────
void NdkCamera::onDeviceDisconnected(void* ctx, ACameraDevice*) {
    LOGI("Camera disconnected");
    auto* self = static_cast<NdkCamera*>(ctx);
    self->m_open.store(false);
}

void NdkCamera::onDeviceError(void* ctx, ACameraDevice*, int err) {
    LOGE("Camera device error: %d", err);
    auto* self = static_cast<NdkCamera*>(ctx);
    self->m_open.store(false);
}

// ─── Session callbacks ─────────────────────────────────────────────────────
void NdkCamera::onSessionReady(void*, ACameraCaptureSession*) { LOGI("Session ready"); }
void NdkCamera::onSessionClosed(void*, ACameraCaptureSession*) { LOGI("Session closed"); }
void NdkCamera::onSessionActive(void*, ACameraCaptureSession*) { LOGI("Session active"); }

// ─── Image available ──────────────────────────────────────────────────────
void NdkCamera::onImageAvailable(void* ctx, AImageReader* reader) {
    auto* self = static_cast<NdkCamera*>(ctx);
    if (!self->m_callback) return;

    AImage* image = nullptr;
    if (AImageReader_acquireLatestImage(reader, &image) != AMEDIA_OK || !image)
        return;

    int32_t w = 0, h = 0;
    AImage_getWidth(image, &w);
    AImage_getHeight(image, &h);

    // Y plane
    uint8_t* y_data = nullptr;  int y_len = 0;
    AImage_getPlaneData(image, 0, &y_data, &y_len);
    int y_stride = 0;
    AImage_getPlaneRowStride(image, 0, &y_stride);

    // UV planes (interleaved NV21 or NV12 depending on device)
    uint8_t* uv_data = nullptr; int uv_len = 0;
    AImage_getPlaneData(image, 1, &uv_data, &uv_len);
    int uv_stride = 0, uv_pixel_stride = 0;
    AImage_getPlaneRowStride(image, 1, &uv_stride);
    AImage_getPlanePixelStride(image, 1, &uv_pixel_stride);

    self->m_callback(y_data, w, h, y_stride, uv_stride, uv_pixel_stride);

    AImage_delete(image);
}

// ─── open ─────────────────────────────────────────────────────────────────
int NdkCamera::open(int width, int height, FrameCallback cb) {
    m_callback = std::move(cb);

    // Create camera manager
    m_camera_mgr = ACameraManager_create();
    if (!m_camera_mgr) { LOGE("ACameraManager_create failed"); return -1; }

    std::string cam_id = select_camera_id();
    if (cam_id.empty()) { LOGE("No camera found"); return -1; }
    LOGI("Opening camera: %s", cam_id.c_str());

    // Open camera device
    m_dev_cbs.context             = this;
    m_dev_cbs.onDisconnected      = onDeviceDisconnected;
    m_dev_cbs.onError             = onDeviceError;
    if (ACameraManager_openCamera(m_camera_mgr, cam_id.c_str(), &m_dev_cbs, &m_camera_dev)
        != ACAMERA_OK) {
        LOGE("ACameraManager_openCamera failed");
        return -1;
    }

    // Create image reader
    if (AImageReader_new(width, height, AIMAGE_FORMAT_YUV_420_888, 2, &m_reader)
        != AMEDIA_OK) {
        LOGE("AImageReader_new failed");
        return -1;
    }
    AImageReader_ImageListener listener{ this, onImageAvailable };
    AImageReader_setImageListener(m_reader, &listener);

    // Get window from reader
    ANativeWindow* window = nullptr;
    AImageReader_getWindow(m_reader, &window);

    // Create capture session output
    ACaptureSessionOutput_create(window, &m_session_out);
    ACaptureSessionOutputContainer_create(&m_out_container);
    ACaptureSessionOutputContainer_add(m_out_container, m_session_out);

    // Session callbacks
    m_ses_cbs.context      = this;
    m_ses_cbs.onReady      = onSessionReady;
    m_ses_cbs.onClosed     = onSessionClosed;
    m_ses_cbs.onActive     = onSessionActive;

    // Create capture session
    if (ACameraDevice_createCaptureSession(m_camera_dev, m_out_container,
                                           &m_ses_cbs, &m_session) != ACAMERA_OK) {
        LOGE("ACameraDevice_createCaptureSession failed");
        return -1;
    }

    // Build repeating request
    ACameraDevice_createCaptureRequest(m_camera_dev,
                                       TEMPLATE_PREVIEW, &m_request);
    ACameraOutputTarget_create(window, &m_out_target);
    ACaptureRequest_addTarget(m_request, m_out_target);

    // Start repeating request
    ACameraCaptureSession_setRepeatingRequest(m_session, nullptr, 1, &m_request, nullptr);

    m_open.store(true);
    LOGI("Camera opened: %dx%d", width, height);
    return 0;
}

// ─── close ────────────────────────────────────────────────────────────────
void NdkCamera::close() {
    m_open.store(false);

    if (m_session) {
        ACameraCaptureSession_stopRepeating(m_session);
        ACameraCaptureSession_close(m_session);
        m_session = nullptr;
    }
    if (m_request) {
        if (m_out_target) ACaptureRequest_removeTarget(m_request, m_out_target);
        ACaptureRequest_free(m_request);
        m_request = nullptr;
    }
    if (m_out_target)    { ACameraOutputTarget_free(m_out_target); m_out_target = nullptr; }
    if (m_out_container) { ACaptureSessionOutputContainer_free(m_out_container); m_out_container = nullptr; }
    if (m_session_out)   { ACaptureSessionOutput_free(m_session_out); m_session_out = nullptr; }
    if (m_reader)        { AImageReader_delete(m_reader); m_reader = nullptr; }
    if (m_camera_dev)    { ACameraDevice_close(m_camera_dev); m_camera_dev = nullptr; }
    if (m_camera_mgr)    { ACameraManager_delete(m_camera_mgr); m_camera_mgr = nullptr; }

    LOGI("Camera closed");
}
