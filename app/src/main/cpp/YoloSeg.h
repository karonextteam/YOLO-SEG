#pragma once
#include <vector>
#include <string>
#include <opencv2/core.hpp>
#include <net.h>   // ncnn

// ─────────────────────────────────────────────────────────────
// Detection result containing bounding box + mask
// ─────────────────────────────────────────────────────────────
struct Object {
    cv::Rect_<float> rect;   // bounding box (x,y,w,h) in original image space
    int              label;  // class index
    float            prob;   // confidence score
    cv::Mat          mask;   // CV_8UC1 pixel mask (same size as input image)
};

// ─────────────────────────────────────────────────────────────
// YoloSeg — wraps NCNN inference for YOLO segmentation models
// Thread-safe: create one instance per thread or guard externally.
// ─────────────────────────────────────────────────────────────
class YoloSeg {
public:
    YoloSeg();
    ~YoloSeg();

    /**
     * Load model from Android asset manager.
     * @param mgr         AAssetManager from Java side
     * @param param_path  e.g. "yolo11n-seg.ncnn.param"
     * @param bin_path    e.g. "yolo11n-seg.ncnn.bin"
     * @param use_vulkan  true = Vulkan GPU, false = CPU
     * @return 0 on success, -1 on failure
     */
    int load(AAssetManager* mgr,
             const char*    param_path,
             const char*    bin_path,
             bool           use_vulkan);

    /**
     * Run segmentation on a BGR image.
     * @param bgr     Input image (CV_8UC3, BGR)
     * @param objects Output list of detected objects with masks
     * @param conf_thresh  Confidence threshold (default 0.25)
     * @param nms_thresh   NMS IoU threshold (default 0.45)
     * @return 0 on success
     */
    int detect(const cv::Mat&        bgr,
               std::vector<Object>&  objects,
               float                 conf_thresh = 0.25f,
               float                 nms_thresh  = 0.45f);

    /**
     * Draw colored mask overlays onto the image (in-place).
     * @param bgr     Image to draw on (modified in-place)
     * @param objects Results from detect()
     * @param alpha   Mask opacity 0.0–1.0 (default 0.5)
     */
    static void draw_objects(cv::Mat&                   bgr,
                             const std::vector<Object>& objects,
                             float                      alpha = 0.5f);

    const char* get_class_name(int label) const;

    bool is_loaded() const { return m_loaded; }

private:
    // ── NCNN network ─────────────────────────────────────────
    ncnn::Net   m_net;
    bool        m_loaded      = false;
    bool        m_use_vulkan  = false;

    // ── Model input/output config ─────────────────────────────
    int  m_target_size      = 640;   // must match exported model
    int  m_num_class        = 80;    // COCO 80 classes
    int  m_mask_proto_dim   = 32;    // YOLO-seg mask proto channels
    int  m_mask_size        = 160;   // proto spatial resolution

    // ── Class colors (one per class, HSV-spaced) ─────────────
    static std::vector<cv::Scalar> s_colors;
    static void init_colors();

    // ── Internal helpers ─────────────────────────────────────
    /**
     * Decode raw NCNN output tensors into Object list.
     * Handles YOLO11-seg / YOLO8-seg output layout:
     *   output0: [1, 116, N]  — boxes + class scores + 32 mask coefficients
     *   output1: [1, 32, H, W] — prototype masks
     */
    int decode_outputs(ncnn::Mat&            output0,
                       ncnn::Mat&            output1,
                       std::vector<Object>&  objects,
                       int                   img_w,
                       int                   img_h,
                       float                 conf_thresh,
                       float                 nms_thresh);

    /**
     * Compute final mask for one detection by combining
     * 32-d coefficient vector with prototype mask tensor.
     */
    cv::Mat compute_mask(const float*       coefs,       // [32]
                         const ncnn::Mat&   proto,       // [32, h, w]
                         const cv::Rect2f&  box_norm,    // normalised box
                         int                orig_w,
                         int                orig_h);

    /**
     * Letterbox resize: pad to square keeping aspect ratio.
     * Returns scale and padding applied so we can map coords back.
     */
    ncnn::Mat letterbox(const cv::Mat& img,
                        int            target,
                        float&         scale,
                        int&           pad_w,
                        int&           pad_h);
};
