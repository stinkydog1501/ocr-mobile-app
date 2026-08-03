package com.kinonn.ocrmobile.core.ocr

import com.kinonn.ocrmobile.core.model.BoundingBox
import com.kinonn.ocrmobile.core.model.OcrBlock
import com.kinonn.ocrmobile.core.model.OcrResult
import kotlinx.coroutines.delay

/**
 * Simulated PP-OCRv5 backend used for development and tests: returns a realistic
 * Singapore NRIC front so the full capture → OCR → parse → review flow can be exercised
 * before the Paddle Lite native library is integrated (Phase 1).
 */
class DemoOcrEngine(
    private val latencyMs: Long = 700,
) : OcrEngine {

    override val name: String = "demo-ppocrv5-sim"

    override suspend fun recognize(image: OcrImage): OcrResult {
        delay(latencyMs)
        return OcrResult(
            engineName = name,
            latencyMs = latencyMs,
            blocks = listOf(
                OcrBlock("REPUBLIC OF SINGAPORE", 0.97f, BoundingBox(0.30f, 0.04f, 0.70f, 0.07f)),
                OcrBlock("S1234567A", 0.98f, BoundingBox(0.38f, 0.10f, 0.62f, 0.14f)),
                OcrBlock("NAME", 0.96f, BoundingBox(0.08f, 0.20f, 0.16f, 0.22f)),
                OcrBlock("TAN AH KOW", 0.94f, BoundingBox(0.20f, 0.20f, 0.42f, 0.22f)),
                OcrBlock("RACE", 0.95f, BoundingBox(0.08f, 0.27f, 0.22f, 0.29f)),
                OcrBlock("CHINESE", 0.93f, BoundingBox(0.26f, 0.27f, 0.38f, 0.29f)),
                OcrBlock("SEX", 0.95f, BoundingBox(0.08f, 0.34f, 0.14f, 0.36f)),
                OcrBlock("MALE", 0.94f, BoundingBox(0.20f, 0.34f, 0.30f, 0.36f)),
                OcrBlock("DATE OF BIRTH", 0.96f, BoundingBox(0.08f, 0.41f, 0.30f, 0.43f)),
                OcrBlock("01-01-1990", 0.97f, BoundingBox(0.34f, 0.41f, 0.50f, 0.43f)),
                OcrBlock("NATIONALITY", 0.95f, BoundingBox(0.08f, 0.48f, 0.26f, 0.50f)),
                OcrBlock("SINGAPORE CITIZEN", 0.92f, BoundingBox(0.30f, 0.48f, 0.58f, 0.50f)),
                OcrBlock("ADDRESS", 0.94f, BoundingBox(0.08f, 0.55f, 0.22f, 0.57f)),
                OcrBlock("BLK 123 BISHAN STREET 13 #04-01 SINGAPORE 570123", 0.86f, BoundingBox(0.08f, 0.59f, 0.80f, 0.62f)),
            ),
        )
    }
}
