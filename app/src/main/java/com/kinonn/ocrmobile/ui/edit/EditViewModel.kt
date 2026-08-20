package com.kinonn.ocrmobile.ui.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kinonn.ocrmobile.core.model.BoundingBox
import com.kinonn.ocrmobile.core.model.ParsedDocument
import com.kinonn.ocrmobile.data.OcrRepository
import com.kinonn.ocrmobile.data.ScanProgress
import com.kinonn.ocrmobile.data.ScanSession
import com.kinonn.ocrmobile.data.ScanStep
import com.kinonn.ocrmobile.util.ImageDecoding
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditUiState(
    val isProcessing: Boolean = false,
    val step: ScanStep? = null,
    val error: String? = null,
)

sealed interface EditEvent {
    data class NavigateToReview(
        val document: ParsedDocument,
        val imagePath: String,
        val blocks: List<BoundingBox>,
    ) : EditEvent

    data object NoImage : EditEvent
}

@HiltViewModel
class EditViewModel @Inject constructor(
    private val repository: OcrRepository,
) : ViewModel() {

    /** The working bitmap being edited (owned by this VM). */
    var working by mutableStateOf<Bitmap?>(null)
        private set

    private val _uiState = MutableStateFlow(EditUiState())
    val uiState: StateFlow<EditUiState> = _uiState.asStateFlow()

    private val _events = Channel<EditEvent>(Channel.BUFFERED)
    val events: Flow<EditEvent> = _events.receiveAsFlow()

    /** Restore the working image from the scan session (or leave null). */
    fun loadFromSession() {
        val path = ScanSession.imagePath ?: return
        val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return
        working?.recycle()
        working = bmp
    }

    fun isDirty() = working != null

    /** Rotate the working image by [degrees]; pads with white so nothing clips. */
    fun rotate(degrees: Float) {
        val old = working ?: return
        val w = old.width
        val h = old.height
        val rad = Math.toRadians(degrees.toDouble())
        val cosp = abs(cos(rad))
        val sinp = abs(sin(rad))
        val nw = ceil(w * cosp + h * sinp).toInt().coerceAtLeast(1)
        val nh = ceil(w * sinp + h * cosp).toInt().coerceAtLeast(1)

        val matrix = Matrix()
        matrix.postTranslate(nw / 2f - w / 2f, nh / 2f - h / 2f)
        matrix.postRotate(degrees, nw / 2f, nh / 2f)

        val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(old, matrix, null)
        old.recycle()
        working = out
    }

    /**
     * Crop to the rectangle given by [left],[top],[right],[bottom] — all in
     * normalized image space (0..1). Values are clamped to a minimum size.
     */
    fun crop(left: Float, top: Float, right: Float, bottom: Float) {
        val old = working ?: return
        val x = (left.coerceIn(0f, 1f) * old.width).toInt().coerceIn(0, old.width - 1)
        val y = (top.coerceIn(0f, 1f) * old.height).toInt().coerceIn(0, old.height - 1)
        val r = (right.coerceIn(0f, 1f) * old.width).toInt().coerceIn(x + 1, old.width)
        val b = (bottom.coerceIn(0f, 1f) * old.height).toInt().coerceIn(y + 1, old.height)
        val w = r - x
        val h = b - y

        val out = Bitmap.createBitmap(old, x, y, w, h)
        old.recycle()
        working = out
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Run OCR on the (possibly edited) working image and navigate to review.
     */
    fun accept(context: Context) {
        val bmp = working
        if (bmp == null) {
            _events.trySend(EditEvent.NoImage)
            return
        }
        if (_uiState.value.isProcessing) return
        _uiState.value = EditUiState(isProcessing = true)

        // Keep a review copy of the exact bitmap that was scanned.
        val finalPath = ImageDecoding.cacheBitmap(context, bmp, "scan_final")

        viewModelScope.launch {
            repository.scan(ImageDecoding.toOcrImage(bmp)).collect { progress ->
                when (progress) {
                    is ScanProgress.Step ->
                        _uiState.value = _uiState.value.copy(step = progress.step)
                    is ScanProgress.Done -> {
                        _uiState.value = EditUiState()
                        _events.send(
                            EditEvent.NavigateToReview(
                                document = progress.document,
                                imagePath = finalPath,
                                blocks = progress.result.blocks.map { it.box },
                            )
                        )
                    }
                    is ScanProgress.Failed -> {
                        _uiState.value = EditUiState(error = progress.message)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        working?.recycle()
        working = null
        super.onCleared()
    }
}
