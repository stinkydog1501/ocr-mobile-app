package com.kinonn.ocrmobile.ui.review

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.kinonn.ocrmobile.core.model.BoundingBox
import com.kinonn.ocrmobile.core.model.ParsedDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

data class FieldUi(
    val key: String,
    val label: String,
    val value: String,
    val confidence: Float,
    val needsManualEntry: Boolean,
)

data class ReviewUiState(
    val documentType: String,
    val overallConfidence: Float,
    val needsReview: Boolean,
    val fields: List<FieldUi>,
    val rawText: String,
    val imagePath: String? = null,
    val blocks: List<BoundingBox> = emptyList(),
)

sealed interface ReviewEvent {
    data object Copied : ReviewEvent
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val document: ParsedDocument = Json.decodeFromString(
        savedStateHandle.get<String>(DOCUMENT_ARG) ?: error("Missing $DOCUMENT_ARG argument")
    )

    private val imagePath: String? = savedStateHandle.get<String>(IMAGE_ARG)

    private val blocks: List<BoundingBox> = runCatching {
        Json.decodeFromString<List<BoundingBox>>(
            savedStateHandle.get<String>(BLOCKS_ARG).orEmpty()
        )
    }.getOrDefault(emptyList())

    private val _uiState = MutableStateFlow(
        ReviewUiState(
            documentType = document.documentType.displayName,
            overallConfidence = document.overallConfidence,
            needsReview = document.needsReview,
            fields = document.fields.map {
                FieldUi(it.key, it.label, it.value, it.confidence, it.needsManualEntry)
            },
            rawText = document.rawText,
            imagePath = imagePath,
            blocks = blocks,
        )
    )
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private val _events = Channel<ReviewEvent>(Channel.BUFFERED)
    val events: Flow<ReviewEvent> = _events.receiveAsFlow()

    fun updateFieldValue(key: String, value: String) {
        _uiState.update { state ->
            state.copy(
                fields = state.fields.map { if (it.key == key) it.copy(value = value) else it }
            )
        }
    }

    fun copyJson(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OCR result", buildOutputJson()))
        _events.trySend(ReviewEvent.Copied)
    }

    fun share(context: Context) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, buildOutputJson())
        }
        context.startActivity(Intent.createChooser(intent, "Share OCR result"))
    }

    private fun buildOutputJson(): String {
        val fieldsJson = JsonObject(
            _uiState.value.fields.associate { field ->
                field.key to JsonObject(
                    mapOf(
                        "value" to JsonPrimitive(field.value),
                        "confidence" to JsonPrimitive(field.confidence),
                        "needsManualEntry" to JsonPrimitive(field.needsManualEntry),
                    )
                )
            }
        )
        val output = JsonObject(
            mapOf(
                "document_type" to JsonPrimitive(document.documentType.name),
                "completed_at" to JsonPrimitive(document.completedAt),
                "fields" to fieldsJson,
            )
        )
        return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), output)
    }

    companion object {
        const val DOCUMENT_ARG = "documentJson"
        const val IMAGE_ARG = "imagePath"
        const val BLOCKS_ARG = "blocksJson"
    }
}
