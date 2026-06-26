#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <string>
#include <vector>
#include <map>
#include "YoloSeg.h"

#define TAG  "YoloSegJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static YoloSeg            g_yolo;
static std::vector<Object> g_last_objects;
static std::string        g_last_summary;

static cv::Mat bitmap_to_mat(JNIEnv* env, jobject bitmap) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return cv::Mat();
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return cv::Mat();
    cv::Mat rgba(info.height, info.width, CV_8UC4, pixels);
    cv::Mat bgr;
    cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);
    AndroidBitmap_unlockPixels(env, bitmap);
    return bgr;
}

static jobject mat_to_bitmap(JNIEnv* env, const cv::Mat& bgr) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmap = env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888 = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(configClass, argb8888);
    jobject out_bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmap, bgr.cols, bgr.rows, config);
    if (!out_bitmap) return nullptr;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, out_bitmap, &pixels) < 0) return nullptr;
    cv::Mat rgba(bgr.rows, bgr.cols, CV_8UC4, pixels);
    cv::cvtColor(bgr, rgba, cv::COLOR_BGR2RGBA);
    AndroidBitmap_unlockPixels(env, out_bitmap);
    return out_bitmap;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mavinci_yoloseg_YoloSegJni_loadModel(JNIEnv* env, jclass clazz, jobject asset_mgr, jstring param_path, jstring bin_path, jboolean use_vulkan) {
    AAssetManager* mgr = AAssetManager_fromJava(env, asset_mgr);
    const char* param = env->GetStringUTFChars(param_path, nullptr);
    const char* bin = env->GetStringUTFChars(bin_path, nullptr);
    int ret = g_yolo.load(mgr, param, bin, use_vulkan == JNI_TRUE);
    env->ReleaseStringUTFChars(param_path, param);
    env->ReleaseStringUTFChars(bin_path, bin);
    return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_mavinci_yoloseg_YoloSegJni_segment(JNIEnv* env, jclass clazz, jobject bitmap, jfloat conf_thresh, jfloat nms_thresh, jfloat alpha) {
    if (!bitmap) return nullptr;
    cv::Mat bgr = bitmap_to_mat(env, bitmap);
    if (bgr.empty()) return nullptr;
    g_last_objects.clear();
    if (g_yolo.detect(bgr, g_last_objects, conf_thresh, nms_thresh) == 0) {
        YoloSeg::draw_objects(bgr, g_last_objects, alpha);
        std::map<int, int> counts;
        for (auto& obj : g_last_objects) counts[obj.label]++;
        g_last_summary = "";
        for (auto const& [label, count] : counts) {
            if (!g_last_summary.empty()) g_last_summary += ", ";
            g_last_summary += std::to_string(count) + " " + g_yolo.get_class_name(label);
        }
    } else {
        g_last_summary = "Detection failed";
    }
    return mat_to_bitmap(env, bgr);
}

extern "C" JNIEXPORT jint JNICALL Java_com_mavinci_yoloseg_YoloSegJni_getLastDetectionCount(JNIEnv*, jclass) { return (jint)g_last_objects.size(); }
extern "C" JNIEXPORT jstring JNICALL Java_com_mavinci_yoloseg_YoloSegJni_getLastDetectionSummary(JNIEnv* env, jclass) { return env->NewStringUTF(g_last_summary.c_str()); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_mavinci_yoloseg_YoloSegJni_isModelLoaded(JNIEnv*, jclass) { return g_yolo.is_loaded() ? JNI_TRUE : JNI_FALSE; }

extern "C" JNIEXPORT jobject JNICALL
Java_com_mavinci_yoloseg_YoloSegJni_segmentYuvFrame(JNIEnv* env, jclass clazz, jbyteArray yuv_arr, jint width, jint height, jint y_stride, jint uv_stride, jint uv_pixel_stride, jfloat conf_thresh, jfloat nms_thresh, jfloat alpha) {
    if (!yuv_arr) return nullptr;
    jbyte* yuv = env->GetByteArrayElements(yuv_arr, nullptr);
    if (!yuv) return nullptr;
    cv::Mat yuv_mat(height + height / 2, width, CV_8UC1);
    for (int r = 0; r < height; ++r) memcpy(yuv_mat.ptr(r), (uint8_t*)yuv + r * y_stride, width);
    uint8_t* uv_src = (uint8_t*)yuv + (y_stride * height);
    uint8_t* uv_dst = yuv_mat.ptr(height);
    for (int r = 0; r < height / 2; ++r) {
        if (uv_pixel_stride == 1) memcpy(uv_dst + r * width, uv_src + r * uv_stride, width);
        else {
            for (int c = 0; c < width / 2; ++c) {
                uv_dst[r * width + c * 2] = uv_src[r * uv_stride + c * uv_pixel_stride + 1];
                uv_dst[r * width + c * 2 + 1] = uv_src[r * uv_stride + c * uv_pixel_stride];
            }
        }
    }
    env->ReleaseByteArrayElements(yuv_arr, yuv, JNI_ABORT);
    cv::Mat bgr; cv::cvtColor(yuv_mat, bgr, cv::COLOR_YUV2BGR_NV21);
    std::vector<Object> objects;
    if (g_yolo.detect(bgr, objects, conf_thresh, nms_thresh) == 0) YoloSeg::draw_objects(bgr, objects, alpha);
    return mat_to_bitmap(env, bgr);
}
