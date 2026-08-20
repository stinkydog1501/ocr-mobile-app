# ============================================================================
# CMakeLists.txt — Phase 1 native JNI glue
#
# Builds libpaddle_lite_jni.so (det + cls + rec PP-OCRv5_mobile pipeline).
#
# This is INTENTIONALLY not wired into the default Gradle build, so debug
# builds (which use DemoOcrEngine) do not require an NDK, Paddle Lite or
# OpenCV native SDK. To build the release native binary on a workstation:
#
#   1. NDK + CMake toolchain (Android Studio's bundled toolchain works).
#   2. Paddle Lite arm64 prebuilt: unzip inference_lite_lib.android.armv8
#      → points PADDLE_LITE_DIR at it.
#   3. OpenCV native: Android OpenCV SDK (sdk/native) → OPENCV_ANDROID_SDK.
#
# Build (from repo root):
#   cmake -S app/src/main/cpp -B build/native \
#         -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
#         -DPADDLE_LITE_DIR=/path/to/inference_lite_lib.android.armv8 \
#         -DOPENCV_ANDROID_SDK=/path/to/OpenCV-android-sdk
#   cmake --build build/native --target ocr_mobile_jni
#   # copy build/native/libocr_mobile_jni.so → app/src/main/jniLibs/arm64-v8a/libpaddle_lite_jni.so
#
# (Alternative opt-in Gradle path: ./gradlew :app:assembleRelease -PwithNative)
# ============================================================================
cmake_minimum_required(VERSION 3.22.1)
project(ocr_mobile_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED PADDLE_LITE_DIR)
    set(PADDLE_LITE_DIR "" CACHE PATH "Paddle Lite Android arm64 prebuilt dir")
endif()
if(NOT DEFINED OPENCV_ANDROID_SDK)
    set(OPENCV_ANDROID_SDK "" CACHE PATH "OpenCV-android-sdk root (sdk/native inside)")
endif()

if(NOT PADDLE_LITE_DIR OR NOT OPENCV_ANDROID_SDK)
    message(FATAL_ERROR
        "Phase 1 native build requires -DPADDLE_LITE_DIR=<inference_lite_lib.android.armv8> "
        "and -DOPENCV_ANDROID_SDK=<OpenCV-android-sdk> (see header of this file).")
endif()

# ---- Paddle Lite -----------------------------------------------------------
add_library(paddle_light_api STATIC IMPORTED)
set_target_properties(paddle_light_api PROPERTIES
    IMPORTED_LOCATION
        ${PADDLE_LITE_DIR}/libs/android/arm64-v8a/libpaddle_api_light_bundled.a)
target_include_directories(paddle_light_api INTERFACE
    ${PADDLE_LITE_DIR}/libs/android/include)

# ---- OpenCV (Android native) -----------------------------------------------
add_library(opencv_java STATIC IMPORTED)
set_target_properties(opencv_java PROPERTIES
    IMPORTED_LOCATION
        ${OPENCV_ANDROID_SDK}/sdk/native/staticlibs/arm64-v8a/libopencv_java4.a)
target_include_directories(opencv_java INTERFACE
    ${OPENCV_ANDROID_SDK}/sdk/native/jni/include)

# ---- JNI glue --------------------------------------------------------------
add_library(ocr_mobile_jni SHARED paddle_ocr_jni.cpp)

target_link_libraries(ocr_mobile_jni
    paddle_light_api
    opencv_java
    log)

# Paddle Lite bundles its own maths/jni runtime; make sure we pick it up.
target_link_libraries(ocr_mobile_jni PRIVATE -Wl,--no-undefined)
