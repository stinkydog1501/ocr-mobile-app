package com.kinonn.ocrmobile.core.model

import kotlinx.serialization.Serializable

/**
 * Normalized bounding box (all coordinates 0..1 relative to the source image).
 */
@Serializable
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * One recognized text region from the OCR engine.
 */
@Serializable
data class OcrBlock(
    val text: String,
    val confidence: Float,
    val box: BoundingBox,
)

/**
 * Raw output of an OCR pass, engine-agnostic.
 */
@Serializable
data class OcrResult(
    val blocks: List<OcrBlock>,
    val engineName: String,
    val latencyMs: Long = 0,
) {
    val rawText: String get() = blocks.joinToString("\n") { it.text }
}

/** What a parsed field represents — drives validation and formatting. */
enum class FieldType(val validationHint: String) {
    TEXT("Free text"),
    NAME("Person name"),
    ID_NUMBER("ID number"),
    DATE("Date"),
    AMOUNT("Currency amount"),
    ADDRESS("Address"),
}

/** How a field's value was obtained. */
enum class MatchStrategy { REGEX, KEYWORD, MANUAL }

/** Vertical band of the document the field is expected in (future position matching). */
enum class VerticalZone { TOP, MIDDLE, BOTTOM, ANY }

/** Horizontal band of the document the field is expected in (future position matching). */
enum class HorizontalZone { LEFT, CENTER, RIGHT, ANY }

/**
 * Per-document-type field definition. A schema maps label → extraction rules.
 */
@Serializable
data class FieldSchema(
    val key: String,
    val label: String,
    val type: FieldType,
    /** Regex sources tried against each block. First full-block match wins. */
    val patterns: List<String> = emptyList(),
    /** Label keywords (e.g. "NAME", "DATE OF BIRTH"). Value comes from the same block or the next line. */
    val keywords: List<String> = emptyList(),
    /** If true the regex must match the entire block text (e.g. currency amounts) — prevents partial-digit false hits. */
    val exactBlockMatch: Boolean = false,
    val required: Boolean = false,
    val verticalZone: VerticalZone = VerticalZone.ANY,
    val horizontalZone: HorizontalZone = HorizontalZone.ANY,
)

/** A single extracted field with provenance and confidence. */
@Serializable
data class ExtractedField(
    val key: String,
    val label: String,
    val value: String,
    val confidence: Float,
    val strategy: MatchStrategy,
    val needsManualEntry: Boolean = false,
)

/** Final structured output of one scan. */
@Serializable
data class ParsedDocument(
    val documentType: DocumentType,
    val fields: List<ExtractedField>,
    val rawText: String,
    val overallConfidence: Float,
    val completedAt: Long,
) {
    /** True when anything needs a human look before the output is trusted. */
    val needsReview: Boolean get() = fields.any { it.needsManualEntry || it.confidence < MIN_FIELD_CONFIDENCE }

    companion object {
        const val MIN_FIELD_CONFIDENCE = 0.70f
    }
}

enum class DocumentType(val displayName: String, val schemaKey: String) {
    NRIC("NRIC", "nric"),
    DRIVERS_LICENSE("Driver's License", "drivers_license"),
    BANK_FORM("Bank Form", "bank_form"),
    GENERIC("Generic Text", "generic"),
}
