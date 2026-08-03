package com.kinonn.ocrmobile.core.ocr

import com.kinonn.ocrmobile.core.model.OcrResult

/**
 * Engine-agnostic input image. RGB888 pixel array — keeps this module free of Android types
 * so the whole OCR/parsing pipeline is unit-testable on the JVM.
 */
data class OcrImage(
    val pixelsRgb: ByteArray,
    val width: Int,
    val height: Int,
)

/**
 * Contract every OCR backend implements. Implementations must be safe to call from a
 * background dispatcher (the app wraps calls in its own dispatcher).
 */
interface OcrEngine {
    val name: String
    suspend fun recognize(image: OcrImage): OcrResult
}
