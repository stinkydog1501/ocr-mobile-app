// ============================================================================
// PP-OCRv5_mobile (Paddle Lite) JNI glue — PHASE 1 INTEGRATION SCAFFOLD
// ============================================================================
// This file is the reference implementation for the PaddleLiteOcrEngine native
// methods. It is NOT compiled by the default Gradle build (no externalNativeBuild
// block yet) because it requires the Paddle Lite native library.
//
// To activate (Phase 1, on a dev machine with the Paddle Lite SDK):
//   1. Download the Paddle Lite Android arm64 prebuilt lib:
//      https://github.com/PaddlePaddle/Paddle-Lite/releases (inference_lite_lib.android.armv8)
//   2. Add `externalNativeBuild { cmake { path "src/main/cpp/CMakeLists.txt" } }`
//      to app/build.gradle.kts and link libpaddle_api_light_bundled.a, or copy
//      libpaddle_light_api_shared.so into jniLibs/arm64-v8a.
//   3. Convert PP-OCRv5_mobile det/rec/cls models with paddle_lite_opt and place
//      the .nb files in assets/models/ (see assets/models/README.md).
//   4. The Java class loads libpaddle_lite_jni.so — name this binary accordingly.
//
// JSON contract produced (parsed by com.kinonn.ocrmobile.core.parse.parseOcrResult):
//   {"latency_ms": N, "blocks": [{"text": "...", "confidence": 0.x,
//     "box": {"left":..,"top":..,"right":..,"bottom":..}}]}
// ============================================================================

#include <jni.h>
#include <string>
#include <vector>
#include <sstream>

#include "paddle_api.h"  // Paddle Lite C++ API (paddle_lite_api.h)

// PaddleOCR post-processing is intentionally minimal here; the full DB + CRNN
// post-processing from PaddleOCR's deploy/android_demo should be ported into
// this file (ocr_db_post_process / crnn_process) during Phase 1 integration.

namespace {

using paddle::lite_api::PowerMode;
using paddle::lite_api::TargetType;
using paddle::lite_api::Tensor;

struct OcrEngine {
    std::shared_ptr<paddle::lite_api::PaddlePredictor> det;
    std::shared_ptr<paddle::lite_api::PaddlePredictor> rec;
    std::shared_ptr<paddle::lite_api::PaddlePredictor> cls;
};

std::shared_ptr<paddle::lite_api::PaddlePredictor> load_predictor(
        AAssetManager* assets, const std::string& model_name) {
    // Read model bytes from assets.
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
    return paddle::lite_api::CreatePaddlePredictor<paddle::lite_api::MobileConfig>(config);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kinonn_ocrmobile_ocr_PaddleLiteOcrEngine_nativeInit(
        JNIEnv* env, jobject thiz, jobject assets,
        jstring det_model, jstring rec_model, jstring cls_model) {
    AAssetManager* am = AAssetManager_fromJava(env, assets);
    auto* engine = new OcrEngine();
    engine->det = load_predictor(am, "models/det.nb");
    engine->rec = load_predictor(am, "models/rec.nb");
    engine->cls = load_predictor(am, "models/cls.nb");
    if (!engine->det || !engine->rec) {
        delete engine;
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jstring JNICALL
Java_com_kinonn_ocrmobile_ocr_PaddleLiteOcrEngine_nativeRunOcr(
        JNIEnv* env, jobject thiz, jlong ptr, jbyteArray rgb, jint width, jint height) {
    if (ptr == 0) return env->NewStringUTF("{\"blocks\":[],\"latency_ms\":0}");

    // TODO(Phase 1): port the PaddleOCR pipeline:
    //  1. det: resize to model input, normalize, run DB detection → text boxes
    //  2. cls: run orientation classifier per box, rotate crops
    //  3. rec: run CRNN recognition per crop, decode with ppocr_keys_v1.txt
    //  4. Build the JSON contract below with normalized box coordinates.

    std::string json = "{\"latency_ms\":0,\"blocks\":[]}";
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_com_kinonn_ocrmobile_ocr_PaddleLiteOcrEngine_nativeRelease(
        JNIEnv* env, jobject thiz, jlong ptr) {
    if (ptr != 0) delete reinterpret_cast<OcrEngine*>(ptr);
}

}  // extern "C"
