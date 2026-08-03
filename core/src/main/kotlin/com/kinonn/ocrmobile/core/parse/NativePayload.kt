package com.kinonn.ocrmobile.core.parse

import com.kinonn.ocrmobile.core.model.BoundingBox
import com.kinonn.ocrmobile.core.model.OcrBlock
import com.kinonn.ocrmobile.core.model.OcrResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Parses the JSON contract produced by the native Paddle Lite JNI layer.
 *
 * Contract:
 * {
 *   "latency_ms": 123,
 *   "blocks": [
 *     {"text": "S1234567A", "confidence": 0.98,
 *      "box": {"left": 0.1, "top": 0.2, "right": 0.5, "bottom": 0.3}}
 *   ]
 * }
 */
@Serializable
private data class NativeBlock(
    val text: String,
    val confidence: Float,
    val box: NativeBox,
)

@Serializable
private data class NativeBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

@Serializable
private data class NativePayload(
    val blocks: List<NativeBlock>,
    @SerialName("latency_ms") val latencyMs: Long = 0,
)

fun parseOcrResult(json: String, engineName: String): OcrResult {
    val payload = Json { ignoreUnknownKeys = true }.decodeFromString<NativePayload>(json)
    return OcrResult(
        blocks = payload.blocks.map { block ->
            OcrBlock(
                text = block.text,
                confidence = block.confidence,
                box = BoundingBox(block.box.left, block.box.top, block.box.right, block.box.bottom),
            )
        },
        engineName = engineName,
        latencyMs = payload.latencyMs,
    )
}
