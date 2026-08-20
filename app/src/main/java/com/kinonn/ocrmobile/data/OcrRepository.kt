package com.kinonn.ocrmobile.data

import com.kinonn.ocrmobile.core.model.DocumentType
import com.kinonn.ocrmobile.core.model.OcrResult
import com.kinonn.ocrmobile.core.model.ParsedDocument
import com.kinonn.ocrmobile.core.ocr.OcrEngine
import com.kinonn.ocrmobile.core.ocr.OcrImage
import com.kinonn.ocrmobile.core.parse.FieldExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/** Stages surfaced to the UI while a scan is in flight. */
enum class ScanStep(val label: String) {
    PREPARING("Preparing image"),
    DETECTING("Detecting text"),
    RECOGNIZING("Recognizing text"),
    PARSING("Extracting fields"),
}

sealed interface ScanProgress {
    data class Step(val step: ScanStep) : ScanProgress
    data class Done(val document: ParsedDocument, val result: OcrResult) : ScanProgress
    data class Failed(val message: String) : ScanProgress
}

interface OcrRepository {
    fun scan(image: OcrImage): Flow<ScanProgress>
}

/**
 * Runs the OCR pipeline off the main thread and streams progress:
 * image → engine.recognize() → type detection → field extraction → ParsedDocument.
 *
 * Low-confidence results are surfaced through [ParsedDocument.needsReview]
 * (set by [FieldExtractor] when fields fall below threshold or fail
 * validation) — the review screen prompts the user to re-capture.
 * Re-running the same image through the same engine would be pointless:
 * OCR is deterministic for identical input.
 */
class DefaultOcrRepository @Inject constructor(
    private val engine: OcrEngine,
    private val extractor: FieldExtractor,
) : OcrRepository {

    override fun scan(image: OcrImage): Flow<ScanProgress> = flow {
        emit(ScanProgress.Step(ScanStep.PREPARING))
        emit(ScanProgress.Step(ScanStep.DETECTING))
        val result = engine.recognize(image)
        emit(ScanProgress.Step(ScanStep.RECOGNIZING))
        val type = extractor.detectDocumentType(result, DocumentType.entries)
        emit(ScanProgress.Step(ScanStep.PARSING))
        emit(ScanProgress.Done(extractor.parse(result, type), result))
    }
        .flowOn(Dispatchers.Default)
        .catch { error ->
            emit(ScanProgress.Failed(error.message ?: "OCR failed"))
        }
}

