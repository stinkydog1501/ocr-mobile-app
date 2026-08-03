package com.kinonn.ocrmobile.ui.review

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
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

    private val _uiState = MutableStateFlow(
        ReviewUiState(
            documentType = document.documentType.displayName,
            overallConfidence = document.overallConfidence,
            needsReview = document.needsReview,
            fields = document.fields.map {
                FieldUi(it.key, it.label, it.value, it.confidence, it.needsManualEntry)
            },
            rawText = document.rawText,
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
        val output = JsonObject(
            buildMap {
                put("document_type", JsonPrimitive(document.documentType.name))
                put("completed_at", JsonPrimitive(document.completedAt))
                put(
                    "fields",
                    JsonObject(_uiState.value.fields.associate { it.key to JsonPrimitive(it.value) }),
                )
            }
        )
        return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), output)
    }

    companion object {
        const val DOCUMENT_ARG = "documentJson"
    }
}
