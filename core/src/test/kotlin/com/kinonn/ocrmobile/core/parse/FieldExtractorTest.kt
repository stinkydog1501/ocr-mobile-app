package com.kinonn.ocrmobile.core.parse

import com.kinonn.ocrmobile.core.model.BoundingBox
import com.kinonn.ocrmobile.core.model.DocumentType
import com.kinonn.ocrmobile.core.model.MatchStrategy
import com.kinonn.ocrmobile.core.model.OcrBlock
import com.kinonn.ocrmobile.core.model.OcrResult
import com.kinonn.ocrmobile.core.ocr.DemoOcrEngine
import com.kinonn.ocrmobile.core.ocr.OcrImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldExtractorTest {

    private val extractor = FieldExtractor()

    /** Builds an OcrResult with top-to-bottom block ordering (y = index * 0.05). */
    private fun blocks(vararg entries: Pair<String, Float>): OcrResult {
        val list = entries.mapIndexed { i, (text, confidence) ->
            val top = i * 0.05f
            OcrBlock(text, confidence, BoundingBox(0.0f, top, 0.5f, top + 0.02f))
        }
        return OcrResult(blocks = list, engineName = "test")
    }

    private fun fieldOf(doc: com.kinonn.ocrmobile.core.model.ParsedDocument, key: String) =
        doc.fields.firstOrNull { it.key == key }

    @Test
    fun `demo engine pipelines end to end into a full NRIC parse`() = runBlocking {
        val engine = DemoOcrEngine(latencyMs = 0)
        val result = engine.recognize(OcrImage(ByteArray(0), 1, 1))

        val doc = extractor.parse(result, DocumentType.NRIC)

        assertEquals("TAN AH KOW", fieldOf(doc, "name")?.value)
        assertEquals(MatchStrategy.KEYWORD, fieldOf(doc, "name")?.strategy)
        assertEquals("S1234567A", fieldOf(doc, "nric_number")?.value)
        assertEquals(MatchStrategy.REGEX, fieldOf(doc, "nric_number")?.strategy)
        assertEquals("01-01-1990", fieldOf(doc, "date_of_birth")?.value)
        assertEquals("CHINESE", fieldOf(doc, "race")?.value)
        assertEquals("MALE", fieldOf(doc, "sex")?.value)
        assertEquals("SINGAPORE CITIZEN", fieldOf(doc, "nationality")?.value)
        assertTrue(fieldOf(doc, "address")!!.value.contains("BISHAN"))
        assertFalse(doc.needsReview)
        assertTrue(doc.overallConfidence > 0.7f)
    }

    @Test
    fun `detectDocumentType identifies NRIC from demo blocks`() = runBlocking {
        val result = DemoOcrEngine(latencyMs = 0).recognize(OcrImage(ByteArray(0), 1, 1))
        assertEquals(DocumentType.NRIC, extractor.detectDocumentType(result, DocumentType.entries))
    }

    @Test
    fun `keyword with inline value extracts remainder after separator`() {
        val doc = extractor.parse(blocks("NAME: TAN AH KOW" to 0.9f), DocumentType.NRIC)
        assertEquals("TAN AH KOW", fieldOf(doc, "name")?.value)
    }

    @Test
    fun `invalid calendar date flags manual entry`() {
        val doc = extractor.parse(
            blocks("DATE OF BIRTH" to 0.95f, "31-02-1990" to 0.97f),
            DocumentType.NRIC,
        )
        val dob = fieldOf(doc, "date_of_birth")
        assertNotNull(dob)
        assertTrue(dob!!.needsManualEntry)
        assertTrue(dob.confidence < 0.55f)
    }

    @Test
    fun `missing required field is flagged for manual entry`() {
        val doc = extractor.parse(blocks("NAME" to 0.9f, "TAN AH KOW" to 0.9f), DocumentType.NRIC)
        val nric = fieldOf(doc, "nric_number")
        assertNotNull(nric)
        assertTrue(nric!!.needsManualEntry)
        assertEquals("", nric.value)
        assertTrue(doc.needsReview)
    }

    @Test
    fun `bank form extracts amount account date and name`() {
        val doc = extractor.parse(
            blocks(
                "AMOUNT" to 0.95f,
                "\$1,234.56" to 0.97f,
                "ACCOUNT NUMBER" to 0.95f,
                "123456789012" to 0.98f,
                "DATE" to 0.95f,
                "15-07-2026" to 0.97f,
                "NAME" to 0.9f,
                "TAN AH KOW" to 0.9f,
            ),
            DocumentType.BANK_FORM,
        )
        assertEquals("\$1,234.56", fieldOf(doc, "amount")?.value)
        assertEquals("123456789012", fieldOf(doc, "account_number")?.value)
        assertEquals("15-07-2026", fieldOf(doc, "date")?.value)
        assertEquals("TAN AH KOW", fieldOf(doc, "name")?.value)
        assertFalse(fieldOf(doc, "amount")!!.needsManualEntry)
    }

    @Test
    fun `amount regex does not partial-match account digits`() {
        val doc = extractor.parse(
            blocks("123456789012" to 0.98f, "AMOUNT" to 0.95f, "\$45.00" to 0.96f),
            DocumentType.BANK_FORM,
        )
        assertEquals("\$45.00", fieldOf(doc, "amount")?.value)
    }

    @Test
    fun `generic document returns full raw text`() {
        val doc = extractor.parse(blocks("HELLO" to 0.9f, "WORLD" to 0.8f), DocumentType.GENERIC)
        assertEquals("HELLO\nWORLD", fieldOf(doc, "full_text")?.value)
    }

    @Test
    fun `non-matching text falls back to generic`() {
        val result = blocks("SOME UNRELATED TEXT" to 0.9f, "NO KEYWORDS HERE" to 0.9f)
        assertEquals(DocumentType.GENERIC, extractor.detectDocumentType(result, DocumentType.entries))
    }
}
