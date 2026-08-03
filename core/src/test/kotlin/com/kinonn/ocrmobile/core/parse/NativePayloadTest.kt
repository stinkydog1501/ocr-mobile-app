package com.kinonn.ocrmobile.core.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePayloadTest {

    @Test
    fun `parses native JSON contract`() {
        val json = """
            {
              "latency_ms": 142,
              "blocks": [
                {"text": "S1234567A", "confidence": 0.98,
                 "box": {"left": 0.38, "top": 0.10, "right": 0.62, "bottom": 0.14}},
                {"text": "NAME", "confidence": 0.96,
                 "box": {"left": 0.08, "top": 0.20, "right": 0.16, "bottom": 0.22}}
              ]
            }
        """.trimIndent()

        val result = parseOcrResult(json, engineName = "pp-ocrv5-mobile")

        assertEquals("pp-ocrv5-mobile", result.engineName)
        assertEquals(142L, result.latencyMs)
        assertEquals(2, result.blocks.size)
        assertEquals("S1234567A", result.blocks[0].text)
        assertEquals(0.98f, result.blocks[0].confidence)
        assertEquals(0.38f, result.blocks[0].box.left)
        assertEquals("NAME", result.blocks[1].text)
        assertEquals("S1234567A\nNAME", result.rawText)
    }

    @Test
    fun `tolerates unknown fields in native JSON`() {
        val json = """{"extra_field": true, "blocks": [], "latency_ms": 1}"""
        val result = parseOcrResult(json, engineName = "test")
        assertEquals(0, result.blocks.size)
    }
}
