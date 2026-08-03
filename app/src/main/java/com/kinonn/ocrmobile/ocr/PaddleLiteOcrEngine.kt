package com.kinonn.ocrmobile.ocr

import android.content.Context
import android.content.res.AssetManager
import com.kinonn.ocrmobile.core.model.OcrResult
import com.kinonn.ocrmobile.core.ocr.OcrEngine
import com.kinonn.ocrmobile.core.ocr.OcrImage
import com.kinonn.ocrmobile.core.parse.parseOcrResult

/**
 * PP-OCRv5_mobile (Paddle Lite) backend.
 *
 * Phase 1 integration contract:
 *  - app/src/main/jniLibs/arm64-v8a/libpaddle_lite_jni.so  (JNI glue, see app/src/main/cpp/)
 *  - app/src/main/assets/models/det.nb, rec.nb, cls.nb     (converted with paddle_lite_opt)
 *  - app/src/main/assets/models/ppocr_keys_v1.txt          (recognition dictionary)
 *
 * The native layer returns the JSON contract parsed by [parseOcrResult]:
 *   {"latency_ms": N, "blocks": [{"text": "...", "confidence": 0.x,
 *     "box": {"left":..,"top":..,"right":..,"bottom":..}}]}
 */
class PaddleLiteOcrEngine(context: Context) : OcrEngine {

    override val name: String = "pp-ocrv5-mobile"

    private var nativePtr: Long = 0
    private val nativeReady: Boolean

    init {
        val loaded = try {
            System.loadLibrary("paddle_lite_jni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        nativeReady = loaded
        if (loaded) {
            nativePtr = nativeInit(context.assets, MODEL_DET, MODEL_REC, MODEL_CLS)
        }
    }

    override suspend fun recognize(image: OcrImage): OcrResult {
        check(nativeReady) {
            "Paddle Lite native library is not bundled. Add libpaddle_lite_jni.so and " +
                "model .nb files (see README, Phase 1) or run a debug build with the demo engine."
        }
        val json = nativeRunOcr(nativePtr, image.pixelsRgb, image.width, image.height)
        return parseOcrResult(json, engineName = name)
    }

    private external fun nativeInit(
        assets: AssetManager,
        detModel: String,
        recModel: String,
        clsModel: String,
    ): Long

    private external fun nativeRunOcr(ptr: Long, rgb: ByteArray, width: Int, height: Int): String

    private external fun nativeRelease(ptr: Long)

    @Suppress("DEPRECATION")
    protected fun finalize() {
        if (nativeReady && nativePtr != 0L) nativeRelease(nativePtr)
    }

    companion object {
        private const val MODEL_DET = "models/det.nb"
        private const val MODEL_REC = "models/rec.nb"
        private const val MODEL_CLS = "models/cls.nb"
    }
}
