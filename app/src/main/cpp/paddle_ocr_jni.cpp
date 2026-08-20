// ============================================================================
// paddle_ocr_jni.cpp — PP-OCRv5_mobile (Paddle Lite) JNI glue — PHASE 1
// ============================================================================
// Complete on-device pipeline: det (DB) → cls (orientation) → rec (CRNN),
// producing the JSON contract parsed by
// com.kinonn.ocrmobile.core.parse.parseOcrResult:
//
//   {"latency_ms": N, "blocks":[{"text":"...","confidence":0.x,
//     "box":{"left":..,"top":..,"right":..,"bottom":..}}]}
//
// The algorithmic core (box geometry, unclip, CTC decode, JSON) lives in the
// pure header ocr_postprocess.h, which is unit-tested on the host (g++). This
// file only does tensor/preprocess/OpenCV I/O.
//
// Build prerequisites (workstation — see README, Phase 1):
//   - Android NDK + Paddle Lite arm64 prebuilt lib (libpaddle_light_api_shared.so
//     or libpaddle_api_light_bundled.a) + the Paddle Lite C++ headers.
//   - assets/models/{det,rec,cls}.nb + ppocr_keys_v1.txt.
// ============================================================================

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#include <chrono>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/photo.hpp>

#include "paddle_api.h"  // Paddle Lite C++ API
#include "ocr_postprocess.h"

namespace {

using paddle::lite_api::PowerMode;
using paddle::lite_api::TargetType;
using paddle::lite_api::Tensor;
using paddle::lite_api::PaddlePredictor;

// ---- PP-OCRv5_mobile inference dimensions ---------------------------------
constexpr int kDetInput = 960;           // det is letterboxed to 960x960
constexpr int kRecHeight = 48;           // rec input height (fixed)
constexpr int kRecMaxWidth = 320;        // rec input width cap
constexpr int kClsWidth = 192;           // cls input 3x48x192
constexpr int kClsHeight = 48;

// ---- PP-OCR thresholds / ratios (PaddleOCR defaults) -----------------------
constexpr float kDetDbThresh = 0.3f;     // probability map threshold
constexpr float kDetBoxThresh = 0.6f;    // per-box score threshold
constexpr float kDetUnclipRatio = 1.5f;  // box expansion ratio
constexpr float kClsThresh = 0.9f;       // -> rotate 180 if cls says so beyond this

// ImageNet normalization used by PP-OCR.
constexpr float kMean[3] = {0.485f, 0.456f, 0.406f};
constexpr float kStd[3] = {0.229f, 0.224f, 0.225f};

struct OcrEngine {
    std::shared_ptr<PaddlePredictor> det;
    std::shared_ptr<PaddlePredictor> rec;
    std::shared_ptr<PaddlePredictor> cls;
    std::vector<std::string> dict;  // index 0 = blank placeholder, then chars
};

// ---------------------------------------------------------------------------
// Model loading from assets
// ---------------------------------------------------------------------------
std::shared_ptr<PaddlePredictor> load_predictor(AAssetManager* assets,
                                                const std::string& model_name) {
    AAsset* asset = AAssetManager_open(assets, model_name.c_str(), AASSET_MODE_BUFFER);
    if (asset == nullptr) return nullptr;
    size_t size = AAsset_getLength(asset);
    std::vector<char> buffer(size);
    AAsset_read(asset, buffer.data(), size);
    AAsset_close(asset);

    paddle::lite_api::MobileConfig config;
    config.set_model_from_buffer(buffer.data(), size);
    config.set_power_mode(PowerMode::LITE_POWER_HIGH);
    config.set_threads(2);
    return paddle::lite_api::CreatePaddlePredictor<
        paddle::lite_api::MobileConfig>(config);
}

// Read the recognition dictionary (blank placeholder first).
std::vector<std::string> load_dict(AAssetManager* assets,
                                   const std::string& path) {
    std::vector<std::string> dict;
    AAsset* asset = AAssetManager_open(assets, path.c_str(), AASSET_MODE_BUFFER);
    if (asset == nullptr) return dict;
    size_t size = AAsset_getLength(asset);
    std::vector<char> buffer(size + 1, '\0');
    AAsset_read(asset, buffer.data(), size);
    AAsset_close(asset);

    std::string line;
    std::istringstream ss(buffer.data());
    while (std::getline(ss, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty()) continue;
        dict.push_back(line);
    }
    dict.insert(dict.begin(), "");  // index 0 -> blank placeholder
    return dict;
}

// ---------------------------------------------------------------------------
// Preprocessing: RGB crop -> NCHW float tensor, normalized as PP-OCR expects
// ---------------------------------------------------------------------------
void fill_normalized_chw(const cv::Mat& bgr, float* dst, int h, int w) {
    // bgr is CV_8UC3, BGR memory order from OpenCV. Flip to RGB.
    const int plane = h * w;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const cv::Vec3b& p = bgr.at<cv::Vec3b>(y, x);
            const float b = p[0] / 255.0f;
            const float g = p[1] / 255.0f;
            const float r = p[2] / 255.0f;
            dst[0 * plane + y * w + x] = (r - kMean[0]) / kStd[0];
            dst[1 * plane + y * w + x] = (g - kMean[1]) / kStd[1];
            dst[2 * plane + y * w + x] = (b - kMean[2]) / kStd[2];
        }
    }
}

// Resize a crop to (w x h) keeping aspect-ratio-correct BGR, then fill NCHW.
void preprocess_to_tensor(const cv::Mat& src, int h, int w, float* dst) {
    cv::Mat resized;
    cv::resize(src, resized, cv::Size(w, h), 0, 0, cv::INTER_LINEAR);
    fill_normalized_chw(resized, dst, h, w);
}

// ---------------------------------------------------------------------------
// DB detector post-processing (OpenCV-dependent part; math is in the header)
// ---------------------------------------------------------------------------
std::vector<ocrpp::Box> db_postprocess(const float* prob, int pw, int ph,
                                       float img_scale_x, float img_scale_y) {
    // Binary mask for contour detection.
    cv::Mat bin(ph, pw, CV_8UC1);
    for (int i = 0; i < pw * ph; ++i) {
        bin.data[i] = (prob[i] >= kDetDbThresh) ? 255 : 0;
    }
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(bin, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);

    std::vector<ocrpp::Box> boxes;
    for (const auto& c : contours) {
        if (c.size() < 4) continue;
        const cv::RotatedRect rr = cv::minAreaRect(c);
        ocrpp::Box box{{rr.center.x, rr.center.y}, rr.size.width, rr.size.height,
                       rr.angle};
        // Discard degenerate boxes.
        if (box.w < 2.0f || box.h < 2.0f) continue;
        // Average probability inside the box must beat the box threshold.
        if (ocrpp::box_score(prob, pw, ph, box) < kDetBoxThresh) continue;
        // Expand (unclip) then bound to the det-space image.
        box = ocrpp::unclip(box, kDetUnclipRatio);
        ocrpp::clip_box_to_image(box, static_cast<float>(pw), static_cast<float>(ph));
        // Re-score the expanded box.
        if (ocrpp::box_score(prob, pw, ph, box) < kDetBoxThresh) continue;
        // Scale from det-space into original image space.
        box.center.x *= img_scale_x;
        box.center.y *= img_scale_y;
        box.w *= img_scale_x;
        box.h *= img_scale_y;
        boxes.push_back(box);
    }
    ocrpp::sort_reading_order(boxes);
    return boxes;
}

// Warp a detected box (in original image space) into a rect of (h x w),
// perspective-correcting the crop. Returns a BGR crop.
bool warp_crop(const cv::Mat& img, const ocrpp::Box& box, int outW, int outH,
               cv::Mat& out) {
    const auto pts = ocrpp::rotated_rect_points(box);
    // Source quad (4 corners of the box).
    std::vector<cv::Point2f> src(4);
    for (int i = 0; i < 4; ++i) src[i] = cv::Point2f(pts[i].x, pts[i].y);
    std::vector<cv::Point2f> dst(4);
    dst[0] = cv::Point2f(0, 0);
    dst[1] = cv::Point2f(static_cast<float>(outW), 0);
    dst[2] = cv::Point2f(static_cast<float>(outW), static_cast<float>(outH));
    dst[3] = cv::Point2f(0, static_cast<float>(outH));
    const cv::Mat M = cv::getPerspectiveTransform(src, dst);
    cv::warpPerspective(img, out, M, cv::Size(outW, outH), cv::INTER_LINEAR,
                        cv::BORDER_REPLICATE);
    return !out.empty();
}

// Compute the recognition input width from the box aspect ratio (48-high).
int rec_width_for_box(const ocrpp::Box& box) {
    const auto pts = ocrpp::rotated_rect_points(box);
    const float w1 = std::hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y);
    const float w2 = std::hypot(pts[2].x - pts[3].x, pts[2].y - pts[3].y);
    const float h1 = std::hypot(pts[3].x - pts[0].x, pts[3].y - pts[0].y);
    const float h2 = std::hypot(pts[2].x - pts[1].x, pts[2].y - pts[1].y);
    const float boxW = std::max(w1, w2);
    const float boxH = std::max(h1, h2);
    int w = (boxH > 1e-4f)
        ? static_cast<int>(kRecHeight * boxW / boxH)
        : kRecHeight;
    w = static_cast<int>(w / 8.0f + 0.5f) * 8;   // align to 8 for speed
    return std::clamp(w, 8, kRecMaxWidth);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kinonn_ocrmobile_ocr_PaddleLiteOcrEngine_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jobject assets,
        jstring det_model, jstring rec_model, jstring cls_model) {
    AAssetManager* am = AAssetManager_fromJava(env, assets);
    const char* det_c = env->GetStringUTFChars(det_model, nullptr);
    const char* rec_c = env->GetStringUTFChars(rec_model, nullptr);
    const char* cls_c = env->GetStringUTFChars(cls_model, nullptr);

    auto* engine = new OcrEngine();
    engine->det = load_predictor(am, det_c);
    engine->rec = load_predictor(am, rec_c);
    engine->cls = load_predictor(am, cls_c);
    engine->dict = load_dict(am, "models/ppocr_keys_v1.txt");

    env->ReleaseStringUTFChars(det_model, det_c);
    env->ReleaseStringUTFChars(rec_model, rec_c);
    env->ReleaseStringUTFChars(cls_model, cls_c);

    if (!engine->det || !engine->rec || !engine->cls || engine->dict.empty()) {
        delete engine;
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jstring JNICALL
Java_com_kinonn_ocrmobile_ocr_PaddleLiteOcrEngine_nativeRunOcr(
        JNIEnv* env, jobject /*thiz*/, jlong ptr, jbyteArray rgb,
        jint width, jint height) {
    if (ptr == 0) return env->NewStringUTF("{\"latency_ms\":0,\"blocks\":[]}");
    auto* engine = reinterpret_cast<OcrEngine*>(ptr);
    if (!engine || !engine->det || !engine->rec || engine->dict.empty())
        return env->NewStringUTF("{\"latency_ms\":0,\"blocks\":[]}");

    const auto t0 = std::chrono::steady_clock::now();

    // Wrap the incoming RGB888 buffer into a contiguous BGR cv::Mat we own.
    jsize len = env->GetArrayLength(rgb);
    std::vector<jbyte> raw(len);
    env->GetByteArrayRegion(rgb, 0, len, raw.data());
    cv::Mat bgr(height, width, CV_8UC3);
    for (jsize i = 0; i + 2 < len; i += 3) {
        const unsigned char r = static_cast<unsigned char>(raw[i]);
        const unsigned char g = static_cast<unsigned char>(raw[i + 1]);
        const unsigned char b = static_cast<unsigned char>(raw[i + 2]);
        bgr.data[i] = b;
        bgr.data[i + 1] = g;
        bgr.data[i + 2] = r;
    }

    const float scaleX = static_cast<float>(width) / kDetInput;
    const float scaleY = static_cast<float>(height) / kDetInput;

    // ---- DET ---------------------------------------------------------------
    const int detPlane = kDetInput * kDetInput;
    std::vector<float> detInput(3 * detPlane);
    preprocess_to_tensor(bgr, kDetInput, kDetInput, detInput.data());

    auto detT = engine->det->GetInput(0);
    detT->Resize({1, 3, kDetInput, kDetInput});
    std::copy(detInput.begin(), detInput.end(), detT->mutable_data<float>());
    engine->det->Run();
    auto* detOut = engine->det->GetOutput(0);
    const float* prob = detOut->data<float>();

    const auto boxes =
        db_postprocess(prob, kDetInput, kDetInput, scaleX, scaleY);

    // ---- CLS + REC per box -------------------------------------------------
    std::vector<ocrpp::OutputBlock> blocks;
    for (const auto& box : boxes) {
        const int recW = rec_width_for_box(box);
        cv::Mat crop;
        if (!warp_crop(bgr, box, recW, kRecHeight, crop)) continue;

        // CLS: orientation classifier; rotate 180 if flagged.
        std::vector<float> clsInput(3 * kClsHeight * kClsWidth);
        preprocess_to_tensor(crop, kClsHeight, kClsWidth, clsInput.data());
        auto clsT = engine->cls->GetInput(0);
        clsT->Resize({1, 3, kClsHeight, kClsWidth});
        std::copy(clsInput.begin(), clsInput.end(), clsT->mutable_data<float>());
        engine->cls->Run();
        auto* clsOut = engine->cls->GetOutput(0);
        const int clsClasses = static_cast<int>(clsOut->shape()[1]);
        const float* clsData = clsOut->data<float>();
        const float clsP0 = clsData[0];
        const float clsP1 = clsClasses > 1 ? clsData[1] : 0.0f;
        if (clsClasses > 1 && clsP1 > kClsThresh && clsP1 > clsP0) {
            cv::rotate(crop, crop, cv::ROTATE_180);
        }

        // REC: CRNN recognition.
        std::vector<float> recInput(3 * kRecHeight * recW);
        preprocess_to_tensor(crop, kRecHeight, recW, recInput.data());
        auto recT = engine->rec->GetInput(0);
        recT->Resize({1, 3, kRecHeight, recW});
        std::copy(recInput.begin(), recInput.end(), recT->mutable_data<float>());
        engine->rec->Run();
        auto* recOut = engine->rec->GetOutput(0);
        const auto& recShape = recOut->shape();
        const int seqLen = recShape.size() >= 3 ? static_cast<int>(recShape[1]) : 0;
        const int classes = recShape.size() >= 3 ? static_cast<int>(recShape[2]) : 0;
        if (seqLen <= 0 || classes <= 0) continue;

        const ocrpp::Recognition rec = ocrpp::ctc_greedy_decode(
            engine->dict, recOut->data<float>(), seqLen, classes);
        if (!rec.valid) continue;

        blocks.push_back({rec.text, rec.confidence, box});
    }

    const auto t1 = std::chrono::steady_clock::now();
    const long latencyMs =
        std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

    const std::string json =
        ocrpp::build_json(latencyMs, blocks, static_cast<float>(width),
                          static_cast<float>(height));
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_com_kinonn_ocrmobile_ocr_PaddleLiteOcrEngine_nativeRelease(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    if (ptr != 0) delete reinterpret_cast<OcrEngine*>(ptr);
}

}  // extern "C"
