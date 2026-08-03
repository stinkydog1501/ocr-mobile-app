package com.kinonn.ocrmobile.core.parse

import com.kinonn.ocrmobile.core.model.DocumentType
import com.kinonn.ocrmobile.core.model.ExtractedField
import com.kinonn.ocrmobile.core.model.FieldSchema
import com.kinonn.ocrmobile.core.model.FieldType
import com.kinonn.ocrmobile.core.model.MatchStrategy
import com.kinonn.ocrmobile.core.model.OcrBlock
import com.kinonn.ocrmobile.core.model.OcrResult
import com.kinonn.ocrmobile.core.model.ParsedDocument
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Converts raw OCR blocks into structured fields per document-type schema.
 *
 * Strategy per field, in priority order:
 *  1. REGEX — pattern against block text (exact-block match for amounts).
 *  2. KEYWORD — label block ("NAME"), value inline ("NAME: TAN AH KOW") or on the
 *     next line below the label (typical ID-card layout).
 *  3. Required fields with no source are flagged [ExtractedField.needsManualEntry].
 *
 * Known limitation (Phase 4 tuning): a keyword label row that is itself a composite
 * label, e.g. "RACE / DIALECT", is treated as an inline value ("DIALECT") rather than
 * a label. Mitigation for production: template-specific label lists.
 */
class FieldExtractor(
    private val schemas: Map<DocumentType, List<FieldSchema>> = DocumentSchemas.all,
) {

    companion object {
        /** Below this confidence a field demands a human check regardless of validity. */
        const val MANUAL_ENTRY_THRESHOLD = 0.55f
        private const val REGEX_CONFIDENCE_FACTOR = 0.95f
        private const val KEYWORD_CONFIDENCE_FACTOR = 0.85f
        private const val INVALID_VALUE_PENALTY = 0.4f
    }

    private data class MatchHit(
        val value: String,
        val confidence: Float,
        val strategy: MatchStrategy,
    )

    /** Picks the best-matching document type for a raw OCR result. */
    fun detectDocumentType(result: OcrResult, candidates: List<DocumentType>): DocumentType {
        var best = DocumentType.GENERIC
        var bestScore = 0
        for (type in candidates) {
            if (type == DocumentType.GENERIC) continue
            var score = 0
            for (schema in schemas[type].orEmpty()) {
                for (block in result.blocks) {
                    for (keyword in schema.keywords) {
                        if (keywordRegex(keyword).containsMatchIn(block.text)) score += 2
                    }
                    for (pattern in schema.patterns) {
                        if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(block.text)) score += 3
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score
                best = type
            }
        }
        return best
    }

    /** Structured parse of a raw result for a known document type. */
    fun parse(result: OcrResult, documentType: DocumentType): ParsedDocument {
        if (documentType == DocumentType.GENERIC) return parseGeneric(result)

        val blocks = result.blocks
            .filter { it.text.isNotBlank() }
            .sortedBy { it.box.centerY }
        val used = mutableSetOf<Int>()

        val fields = schemas[documentType].orEmpty().mapNotNull { schema ->
            extractField(blocks, used, schema)
        }
        val overall = if (fields.isEmpty()) 0f
        else fields.map { if (it.needsManualEntry) 0f else it.confidence }.average().toFloat()

        return ParsedDocument(
            documentType = documentType,
            fields = fields,
            rawText = result.rawText,
            overallConfidence = overall,
            completedAt = System.currentTimeMillis(),
        )
    }

    private fun parseGeneric(result: OcrResult): ParsedDocument {
        val confidences = result.blocks.filter { it.text.isNotBlank() }.map { it.confidence }
        val confidence = if (confidences.isEmpty()) 0f else confidences.average().toFloat()
        return ParsedDocument(
            documentType = DocumentType.GENERIC,
            fields = listOf(
                ExtractedField(
                    key = "full_text",
                    label = "Extracted text",
                    value = result.rawText,
                    confidence = confidence,
                    strategy = MatchStrategy.REGEX,
                ),
            ),
            rawText = result.rawText,
            overallConfidence = confidence,
            completedAt = System.currentTimeMillis(),
        )
    }

    private fun extractField(
        blocks: List<OcrBlock>,
        used: MutableSet<Int>,
        schema: FieldSchema,
    ): ExtractedField? {
        tryRegex(blocks, used, schema)?.let { return finish(it, schema) }
        tryKeyword(blocks, used, schema)?.let { return finish(it, schema) }

        return if (schema.required) {
            ExtractedField(
                key = schema.key,
                label = schema.label,
                value = "",
                confidence = 0f,
                strategy = MatchStrategy.MANUAL,
                needsManualEntry = true,
            )
        } else {
            null
        }
    }

    private fun finish(hit: MatchHit, schema: FieldSchema): ExtractedField {
        var confidence = hit.confidence
        var invalid = false
        when (schema.type) {
            FieldType.DATE -> invalid = !DateUtil.isValidDate(hit.value)
            FieldType.AMOUNT -> invalid = !isValidAmount(hit.value)
            else -> Unit
        }
        if (invalid) confidence *= INVALID_VALUE_PENALTY
        val needsManual = invalid || confidence < MANUAL_ENTRY_THRESHOLD
        return ExtractedField(
            key = schema.key,
            label = schema.label,
            value = hit.value,
            confidence = confidence.coerceIn(0f, 1f),
            strategy = hit.strategy,
            needsManualEntry = needsManual,
        )
    }

    private fun tryRegex(
        blocks: List<OcrBlock>,
        used: MutableSet<Int>,
        schema: FieldSchema,
    ): MatchHit? {
        for (patternSource in schema.patterns) {
            val regex = Regex(patternSource, RegexOption.IGNORE_CASE)
            for (index in blocks.indices) {
                if (index in used) continue
                val text = blocks[index].text.trim()
                val match = if (schema.exactBlockMatch) regex.matchEntire(text) else regex.find(text)
                if (match != null && match.value.isNotBlank()) {
                    used.add(index)
                    return MatchHit(
                        value = match.value.trim(),
                        confidence = blocks[index].confidence * REGEX_CONFIDENCE_FACTOR,
                        strategy = MatchStrategy.REGEX,
                    )
                }
            }
        }
        return null
    }

    private fun tryKeyword(
        blocks: List<OcrBlock>,
        used: MutableSet<Int>,
        schema: FieldSchema,
    ): MatchHit? {
        for (index in blocks.indices) {
            if (index in used) continue
            val keyword = schema.keywords.firstOrNull { keywordRegex(it).containsMatchIn(blocks[index].text) }
                ?: continue

            val inline = remainderAfterKeyword(blocks[index].text, keyword)
            if (inline != null) {
                used.add(index)
                return MatchHit(
                    value = inline,
                    confidence = blocks[index].confidence * KEYWORD_CONFIDENCE_FACTOR,
                    strategy = MatchStrategy.KEYWORD,
                )
            }

            // Label-only block: value is the next unused line below it.
            used.add(index)
            val valueIndex = nextUnusedIndex(blocks, used, index + 1) ?: return null
            used.add(valueIndex)
            return MatchHit(
                value = blocks[valueIndex].text.trim(),
                confidence = minOf(blocks[index].confidence, blocks[valueIndex].confidence) * KEYWORD_CONFIDENCE_FACTOR,
                strategy = MatchStrategy.KEYWORD,
            )
        }
        return null
    }

    private fun nextUnusedIndex(blocks: List<OcrBlock>, used: Set<Int>, from: Int): Int? {
        for (i in from until blocks.size) {
            if (i !in used) return i
        }
        return null
    }

    /** Value after "KEYWORD:" separators; null when the block is label-only. */
    private fun remainderAfterKeyword(text: String, keyword: String): String? {
        val match = keywordRegex(keyword).find(text) ?: return null
        val after = text.substring(match.range.last + 1)
        val stripped = after.trimStart(' ', ':', '-', '/', '\t', '\u00A0').trim()
        if (stripped.isBlank() || stripped.length <= 2) return null
        return stripped
    }

    private fun keywordRegex(keyword: String): Regex =
        Regex("\\b" + Regex.escape(keyword) + "\\b", RegexOption.IGNORE_CASE)

    private fun isValidAmount(value: String): Boolean {
        val cleaned = value.replace(" ", "")
            .replace("S$", "$")
            .removePrefix("$")
        return Regex("\\d{1,3}(,\\d{3})*(\\.\\d{2})?").matches(cleaned)
    }

    private object DateUtil {
        private val formats = listOf(
            // uuuu (proleptic year) not yyyy (year-of-era): with STRICT resolver,
            // yyyy cannot resolve to a LocalDate without an explicit era.
            "dd-MM-uuuu", "dd/MM/uuuu", "dd.MM.uuuu", "uuuu-MM-dd", "dd MM uuuu",
        )

        fun isValidDate(value: String): Boolean {
            val trimmed = value.trim()
            for (format in formats) {
                try {
                    // STRICT resolver: rejects impossible dates like 31-02-1990
                    // instead of silently correcting them (SMART default).
                    val date = LocalDate.parse(trimmed, DateTimeFormatter.ofPattern(format).withResolverStyle(ResolverStyle.STRICT))
                    if (date.year in 1900..2100) return true
                } catch (_: DateTimeParseException) {
                    // try next format
                }
            }
            return false
        }
    }
}
