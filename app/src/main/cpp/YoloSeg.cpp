#include "YoloSeg.h"
#include <android/log.h>
#include <algorithm>
#include <numeric>
#include <cmath>
#include <opencv2/imgproc.hpp>

#define TAG "YoloSeg"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

std::vector<cv::Scalar> YoloSeg::s_colors;

void YoloSeg::init_colors() {
    if (!s_colors.empty()) return;
    s_colors.resize(80);
    for (int i = 0; i < 80; ++i) {
        float hue = (float)i * (360.0f / 80.0f);
        cv::Mat hsv(1, 1, CV_8UC3, cv::Scalar(hue / 2.0f, 200, 220));
        cv::Mat bgr;
        cv::cvtColor(hsv, bgr, cv::COLOR_HSV2BGR);
        auto* p = bgr.ptr<uchar>();
        s_colors[i] = cv::Scalar(p[0], p[1], p[2]);
    }
}

static const char* s_class_names[] = {
    "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat","traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat","dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack","umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball","kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket","bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple","sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair","couch","potted plant","bed","dining table","toilet","tv","laptop","mouse","remote","keyboard","cell phone","microwave","oven","toaster","sink","refrigerator","book","clock","vase","scissors","teddy bear","hair drier","toothbrush"
};

static inline float intersection_area(const cv::Rect2f& a, const cv::Rect2f& b) {
    cv::Rect2f inter = a & b;
    return inter.area();
}

static void nms_sorted_bboxes(const std::vector<cv::Rect2f>& bboxes, const std::vector<float>& scores, std::vector<int>& picked, float nms_threshold) {
    picked.clear();
    const int n = (int)bboxes.size();
    if (n == 0) return;
    std::vector<int> indices(n);
    std::iota(indices.begin(), indices.end(), 0);
    std::sort(indices.begin(), indices.end(), [&scores](int i, int j) { return scores[i] > scores[j]; });
    std::vector<float> areas(n);
    for (int i = 0; i < n; i++) areas[i] = bboxes[i].area();
    for (int i = 0; i < n; i++) {
        int idx = indices[i];
        bool keep = true;
        for (int p_idx : picked) {
            float inter_area = intersection_area(bboxes[idx], bboxes[p_idx]);
            float union_area = areas[idx] + areas[p_idx] - inter_area;
            if (inter_area / union_area > nms_threshold) { keep = false; break; }
        }
        if (keep) picked.push_back(idx);
    }
}

static inline float get_feat(const ncnn::Mat& m, int f, int i) {
    if (m.c > 1) {
        if (f < m.c && i < m.w) return m.channel(f)[i];
    } else {
        if (f < m.h && i < m.w) return m.row(f)[i];
    }
    return 0.0f;
}

YoloSeg::YoloSeg() { }
YoloSeg::~YoloSeg() { m_net.clear(); }

int YoloSeg::load(AAssetManager* mgr, const char* param_path, const char* bin_path, bool use_vulkan) {
    init_colors();
    m_use_vulkan = false;
    m_net.clear();
    ncnn::Option opt;
    opt.lightmode = true;
    opt.num_threads = 4;
    opt.use_vulkan_compute = false;
    m_net.opt = opt;
    if (m_net.load_param(mgr, param_path) != 0 || m_net.load_model(mgr, bin_path) != 0) return -1;
    m_loaded = true;
    LOGI("Model %s loaded", param_path);
    return 0;
}

ncnn::Mat YoloSeg::letterbox(const cv::Mat& img, int target, float& scale, int& pad_w, int& pad_h) {
    int w = img.cols; int h = img.rows;
    scale = std::min((float)target / w, (float)target / h);
    int new_w = (int)std::round(w * scale);
    int new_h = (int)std::round(h * scale);
    pad_w = (target - new_w) / 2;
    pad_h = (target - new_h) / 2;
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(img.data, ncnn::Mat::PIXEL_BGR2RGB, w, h, target, target);
    const float norm_vals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
    in.substract_mean_normalize(nullptr, norm_vals);
    return in;
}

int YoloSeg::detect(const cv::Mat& bgr, std::vector<Object>& objects, float conf_thresh, float nms_thresh) {
    if (!m_loaded || bgr.empty()) return -1;
    objects.clear();
    float scale; int pad_w, pad_h;
    ncnn::Mat in = letterbox(bgr, m_target_size, scale, pad_w, pad_h);
    ncnn::Extractor ex = m_net.create_extractor();

    if (ex.input("in0", in) != 0 && ex.input("images", in) != 0 && ex.input(0, in) != 0) return -1;

    ncnn::Mat out0, out1;
    if (ex.extract("out0", out0) == 0 && ex.extract("out1", out1) == 0) { }
    else if (ex.extract("output0", out0) == 0 && ex.extract("output1", out1) == 0) { }
    else { if (ex.extract(0, out0) != 0 || ex.extract(1, out1) != 0) return -1; }

    if (out0.empty() || out1.empty()) return -1;
    if (out0.w != 8400 && out1.w == 8400) std::swap(out0, out1);
    if (out0.w != 8400) { LOGE("Unexpected out0 width: %d", out0.w); return -1; }

    return decode_outputs(out0, out1, objects, bgr.cols, bgr.rows, conf_thresh, nms_thresh);
}

int YoloSeg::decode_outputs(ncnn::Mat& out0, ncnn::Mat& out1, std::vector<Object>& objects, int img_w, int img_h, float conf_thresh, float nms_thresh) {
    int num_pred = out0.w;
    std::vector<cv::Rect2f> boxes; std::vector<float> scores; std::vector<int> class_ids; std::vector<std::vector<float>> mask_coefs;
    float x_factor = (float)img_w / m_target_size; float y_factor = (float)img_h / m_target_size;

    for (int i = 0; i < num_pred; ++i) {
        float best_score = -1.0f; int best_cls = -1;
        for (int c = 0; c < m_num_class; ++c) {
            float s = get_feat(out0, 4 + c, i);
            if (s > best_score) { best_score = s; best_cls = c; }
        }
        if (best_score < conf_thresh) continue;

        float cx = get_feat(out0, 0, i); float cy = get_feat(out0, 1, i);
        float bw = get_feat(out0, 2, i); float bh = get_feat(out0, 3, i);

        boxes.push_back({(cx - bw*0.5f)*x_factor, (cy - bh*0.5f)*y_factor, bw*x_factor, bh*y_factor});
        scores.push_back(best_score); class_ids.push_back(best_cls);

        std::vector<float> coefs(m_mask_proto_dim);
        for (int m = 0; m < m_mask_proto_dim; ++m) {
            coefs[m] = get_feat(out0, 4 + m_num_class + m, i);
        }
        mask_coefs.push_back(coefs);
    }
    std::vector<int> nms_keep; nms_sorted_bboxes(boxes, scores, nms_keep, nms_thresh);
    for (int idx : nms_keep) {
        Object obj; obj.label = class_ids[idx]; obj.prob = scores[idx]; obj.rect = boxes[idx];
        obj.rect.x = std::max(0.0f, obj.rect.x); obj.rect.y = std::max(0.0f, obj.rect.y);
        obj.rect.width = std::min(obj.rect.width, (float)img_w - obj.rect.x); obj.rect.height = std::min(obj.rect.height, (float)img_h - obj.rect.y);
        cv::Rect2f box_norm(boxes[idx].x/img_w, boxes[idx].y/img_h, boxes[idx].width/img_w, boxes[idx].height/img_h);
        obj.mask = compute_mask(mask_coefs[idx].data(), out1, box_norm, img_w, img_h);
        objects.push_back(std::move(obj));
    }
    return 0;
}

cv::Mat YoloSeg::compute_mask(const float* coefs, const ncnn::Mat& proto, const cv::Rect2f& box_norm, int orig_w, int orig_h) {
    int mh = proto.h; int mw = proto.w; int mc = proto.c;
    cv::Mat mask_f(mh, mw, CV_32FC1, cv::Scalar(0.0f));
    for (int i = 0; i < std::min(mc, m_mask_proto_dim); ++i) {
        const float* ptr = (const float*)proto.channel(i);
        if (!ptr) continue;
        cv::Mat proto_ch(mh, mw, CV_32FC1, (void*)ptr);
        mask_f += coefs[i] * proto_ch;
    }
    cv::exp(-mask_f, mask_f); mask_f = 1.0f / (1.0f + mask_f);
    int px1 = std::max(0, (int)(box_norm.x * mw)); int py1 = std::max(0, (int)(box_norm.y * mh));
    int px2 = std::min(mw, (int)((box_norm.x + box_norm.width) * mw)); int py2 = std::min(mh, (int)((box_norm.y + box_norm.height) * mh));
    if (px2 <= px1 || py2 <= py1) return cv::Mat::zeros(orig_h, orig_w, CV_8UC1);
    cv::Mat crop = mask_f(cv::Range(py1, py2), cv::Range(px1, px2));
    cv::Mat resized; cv::resize(crop, resized, cv::Size(std::max(1, (int)(box_norm.width * orig_w)), std::max(1, (int)(box_norm.height * orig_h))), 0, 0, cv::INTER_LINEAR);
    cv::Mat binary; cv::threshold(resized, binary, 0.5f, 255.0, cv::THRESH_BINARY);
    binary.convertTo(binary, CV_8UC1);
    cv::Mat full = cv::Mat::zeros(orig_h, orig_w, CV_8UC1);
    int x = std::max(0, (int)(box_norm.x * orig_w)); int y = std::max(0, (int)(box_norm.y * orig_h));
    int w = std::min(orig_w - x, binary.cols); int h = std::min(orig_h - y, binary.rows);
    if (w > 0 && h > 0) binary(cv::Rect(0, 0, w, h)).copyTo(full(cv::Rect(x, y, w, h)));
    return full;
}

const char* YoloSeg::get_class_name(int label) const { if (label < 0 || label >= 80) return "unknown"; return s_class_names[label]; }

void YoloSeg::draw_objects(cv::Mat& bgr, const std::vector<Object>& objects, float alpha) {
    for (const auto& obj : objects) {
        cv::Scalar color = s_colors[obj.label % 80];
        if (!obj.mask.empty() && obj.mask.rows == bgr.rows && obj.mask.cols == bgr.cols) {
            cv::Mat color_mask(bgr.size(), CV_8UC3, color);
            cv::Mat mask_bgr; cv::cvtColor(obj.mask, mask_bgr, cv::COLOR_GRAY2BGR);
            cv::bitwise_and(color_mask, mask_bgr, mask_bgr);
            cv::addWeighted(bgr, 1.0, mask_bgr, alpha, 0, bgr);
        }
        cv::rectangle(bgr, obj.rect, color, 2);
        std::string txt = std::string(s_class_names[obj.label % 80]) + " " + std::to_string((int)(obj.prob * 100)) + "%";
        cv::putText(bgr, txt, cv::Point((int)obj.rect.x, std::max(0, (int)obj.rect.y - 5)), cv::FONT_HERSHEY_SIMPLEX, 0.5, color, 1);
    }
}
