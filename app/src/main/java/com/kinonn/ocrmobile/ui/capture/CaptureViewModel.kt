package com.kinonn.ocrmobile.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kinonn.ocrmobile.core.model.ParsedDocument
import com.kinonn.ocrmobile.core.ocr.OcrImage
import com.kinonn.ocrmobile.data.OcrRepository
import com.kinonn.ocrmobile.data.ScanProgress
import com.kinonn.ocrmobile.data.ScanStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CaptureUiState(
    val isProcessing: Boolean = false,
    val step: ScanStep? = null,
    val error: String? = null,
)

sealed interface CaptureEvent {
    data class NavigateToReview(val document: ParsedDocument) : CaptureEvent
}

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repository: OcrRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private val _events = Channel<CaptureEvent>(Channel.BUFFERED)
    val events: Flow<CaptureEvent> = _events.receiveAsFlow()

    fun scan(image: OcrImage) {
        if (_uiState.value.isProcessing) return
        _uiState.value = CaptureUiState(isProcessing = true)
        viewModelScope.launch {
            repository.scan(image).collect { progress ->
                when (progress) {
                    is ScanProgress.Step -> _uiState.value = _uiState.value.copy(step = progress.step)
                    is ScanProgress.Done -> {
                        _uiState.value = CaptureUiState()
                        _events.send(CaptureEvent.NavigateToReview(progress.document))
                    }
                    is ScanProgress.Failed -> {
                        _uiState.value = CaptureUiState(error = progress.message)
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun reportCaptureError(message: String) {
        _uiState.value = _uiState.value.copy(error = message)
    }
}
