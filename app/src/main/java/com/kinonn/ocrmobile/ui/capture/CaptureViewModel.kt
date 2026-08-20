package com.kinonn.ocrmobile.ui.capture

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kinonn.ocrmobile.data.ScanSession
import com.kinonn.ocrmobile.util.ImageDecoding
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
    val error: String? = null,
)

sealed interface CaptureEvent {
    /** User captured/selected an image; ready to open the edit step. */
    data object NavigateToEditor : CaptureEvent
}

@HiltViewModel
class CaptureViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private val _events = Channel<CaptureEvent>(Channel.BUFFERED)
    val events: Flow<CaptureEvent> = _events.receiveAsFlow()

    /**
     * Decode a captured/selected image, preprocess it, persist a preview into
     * the scan session, then hand off to the edit (crop/rotate) step.
     */
    fun onImagePicked(context: Context, uri: Uri) {
        if (_uiState.value.isProcessing) return
        _uiState.value = CaptureUiState(isProcessing = true)
        viewModelScope.launch {
            try {
                val scan = ImageDecoding.decodeForScan(context, uri)
                ScanSession.imagePath = ImageDecoding.cacheBitmap(context, scan.preview, "scan")
                scan.preview.recycle()
                _uiState.value = CaptureUiState()
                _events.send(CaptureEvent.NavigateToEditor)
            } catch (e: Exception) {
                _uiState.value = CaptureUiState(error = e.message ?: "Failed to read image")
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
