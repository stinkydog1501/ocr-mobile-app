#!/usr/bin/env bash
# ============================================================================
# fetch_phase1_assets.sh — Phase 1 native asset acquisition (run on a
# WORKSTATION: x86-64 Linux/macOS with Python3 + pip, and the Android NDK).
#
# Downloads the Paddle Lite arm64 prebuilt lib and the PP-OCRv5_mobile
# models, converts them to .nb with paddle_lite_opt, and (optionally) builds
# libpaddle_lite_jni.so — placing everything where the app expects it.
#
#   app/src/main/assets/models/{det,rec,cls}.nb + ppocr_keys_v1.txt
#   app/src/main/jniLibs/arm64-v8a/libpaddle_lite_jni.so
#
# All URLs below were verified reachable (2026-08).
# ============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAGE="${STAGE_DIR:-$REPO_ROOT/.phase1}"
LITE_VER="2.10"
PADDLECLASS="https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model"
CLS_URL="https://paddleocr.bj.bcebos.com/dygraph_v2.0/ch/ch_ppocr_mobile_v2.0_cls_infer.tar"
DICT_URL="https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/release/3.0/ppocr/utils/ppocr_keys_v1.txt"
LITE_TGZ="https://github.com/PaddlePaddle/Paddle-Lite/releases/download/v${LITE_VER}/inference_lite_lib.android.armv8.gcc.c++_shared.with_extra.with_cv.tar.gz"

mkdir -p "$STAGE/models" "$STAGE/lite"
echo ">> staging dir: $STAGE"

dl() { # url, out
    if [ -f "$2" ]; then echo "   cached: $(basename "$2")"; else
        echo "   downloading $(basename "$2") ..."
        curl -fSL --retry 3 -o "$2" "$1"
    fi
}

# --- 1. Paddle Lite lib -----------------------------------------------------
dl "$LITE_TGZ" "$STAGE/lite.tar.gz"
tar -xzf "$STAGE/lite.tar.gz" -C "$STAGE"
# The tarball extracts to inference_lite_lib.android.armv8/ at $STAGE root.
LITE_LIB="$(find "$STAGE" -maxdepth 2 -type d -name 'inference_lite_lib.android.armv8' | head -1)"

# --- 2. Inference models (pdmodel/pdiparams) ---------------------------------
declare -A MODELS=(
    [det]="$PADDLECLASS/paddle3.0.0/PP-OCRv5_mobile_det_infer.tar|$STAGE/models"
    [rec]="$PADDLECLASS/paddle3.0.0/PP-OCRv5_mobile_rec_infer.tar|$STAGE/models"
)
for key in "${!MODELS[@]}"; do
    url="${MODELS[$key]%%|*}"; dir="${MODELS[$key]##*|}"
    dl "$url" "$STAGE/${key}.tar"
    mkdir -p "$dir/$key"
    tar -xf "$STAGE/${key}.tar" -C "$dir/$key" --strip-components=1
done
dl "$CLS_URL" "$STAGE/cls.tar"
mkdir -p "$STAGE/models/cls"; tar -xf "$STAGE/cls.tar" -C "$STAGE/models/cls" --strip-components=1
dl "$DICT_URL" "$STAGE/ppocr_keys_v1.txt"

# --- 3. Convert to .nb -------------------------------------------------------
# paddlelite pip version must match the prediction lib (2.10).
pip install "paddlelite==${LITE_VER}" 2>/dev/null || echo "!! pip install paddlelite failed — install manually, then rerun"
if command -v paddle_lite_opt >/dev/null 2>&1; then
    for m in det rec cls; do
        echo ">> converting $m ..."
        paddle_lite_opt \
            --model_file="$STAGE/models/$m/inference.pdmodel" \
            --param_file="$STAGE/models/$m/inference.pdiparams" \
            --optimize_out="$STAGE/models/$m" \
            --valid_targets=arm \
            --optimize_out_type=naive_buffer
    done
else
    echo "!! paddle_lite_opt not found — run the conversion manually (see README)."
fi

# --- 4. Copy into the app -----------------------------------------------------
echo ">> copying model assets ..."
cp "$STAGE/models/det/det.nb"        "$REPO_ROOT/app/src/main/assets/models/"
cp "$STAGE/models/rec/rec.nb"        "$REPO_ROOT/app/src/main/assets/models/"
cp "$STAGE/models/cls/cls.nb"        "$REPO_ROOT/app/src/main/assets/models/"
cp "$STAGE/ppocr_keys_v1.txt"        "$REPO_ROOT/app/src/main/assets/models/"
ls -lh "$REPO_ROOT/app/src/main/assets/models/"

# --- 5. Build the .so (needs NDK + OpenCV-android-sdk) ------------------------
if [ -n "${ANDROID_NDK:-}" ] && [ -n "${OPENCV_ANDROID_SDK:-}" ]; then
    echo ">> building libpaddle_lite_jni.so ..."
    cmake -S "$REPO_ROOT/app/src/main/cpp" -B "$STAGE/build" \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
        -DPADDLE_LITE_DIR="$LITE_LIB" \
        -DOPENCV_ANDROID_SDK="$OPENCV_ANDROID_SDK"
    cmake --build "$STAGE/build" --target ocr_mobile_jni
    mkdir -p "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
    cp "$STAGE/build/libocr_mobile_jni.so" \
        "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libpaddle_lite_jni.so"
    echo ">> .so copied to app/src/main/jniLibs/arm64-v8a/"
else
    echo "!! set ANDROID_NDK and OPENCV_ANDROID_SDK to build the .so (skipped)."
fi

echo ">> done. Models are NOT committed (gitignored); release build reads them from assets/."
