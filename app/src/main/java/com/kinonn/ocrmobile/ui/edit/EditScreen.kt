package com.kinonn.ocrmobile.ui.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kinonn.ocrmobile.R
import kotlin.math.max
import kotlin.math.min

private const val TAG = "EditScreen"

/** Normalized (0..1) crop rectangle. */
private data class CropRect(var l: Float = 0f, var t: Float = 0f, var r: Float = 1f, var b: Float = 1f)

private const val HANDLE = 24f     // px tap radius for corner handles

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    onDone: () -> Unit,                     // pop back to capture
    onReview: (EditEvent.NavigateToReview) -> Unit,
    viewModel: EditViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val bitmap = viewModel.working

    LaunchedEffect(Unit) { viewModel.loadFromSession() }

    var crop by remember { mutableStateOf(CropRect()) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is EditEvent.NoImage -> onDone()
                is EditEvent.NavigateToReview -> onReview(event)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            EditBottomBar(
                onCancel = onDone,
                onAccept = { viewModel.accept(context) },
                enabled = bitmap != null && !uiState.isProcessing,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (bitmap != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Image + crop overlay.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.edit_preview),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                        CropOverlay(
                            crop = crop,
                            onCropChange = { crop = it },
                        )
                    }

                    EditControls(
                        onRotateLeft = { viewModel.rotate(-90f) },
                        onRotateRight = { viewModel.rotate(90f) },
                        onTiltLeft = { viewModel.rotate(-5f) },
                        onTiltRight = { viewModel.rotate(5f) },
                        onApplyCrop = {
                            viewModel.crop(crop.l, crop.t, crop.r, crop.b)
                            crop = CropRect()
                        },
                        onResetCrop = { crop = CropRect() },
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.edit_no_image),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (uiState.isProcessing) {
                EditProcessingOverlay(stepLabel = uiState.step?.label)
            }

            uiState.error?.let { message ->
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.85f),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = viewModel::clearError) {
                            Text(stringResource(R.string.dismiss))
                        }
                    }
                }
            }
        }
    }
}

/** Draws the crop box and handles drag gestures to resize/reposition it. */
@Composable
private fun CropOverlay(
    crop: CropRect,
    onCropChange: (CropRect) -> Unit,
) {
    var state by remember { mutableStateOf(crop) }
    var mode by remember { mutableStateOf<CropDragMode>(CropDragMode.None) }
    var dragStart by remember { mutableStateOf(CropRect()) }

    // Sync internal state when the parent resets/externalizes the crop rect.
    androidx.compose.runtime.LaunchedEffect(crop.l, crop.t, crop.r, crop.b) {
        state = CropRect(crop.l, crop.t, crop.r, crop.b)
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(crop) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val l = state.l * w
                        val t = state.t * h
                        val r = state.r * w
                        val b = state.b * h
                        mode = when {
                            near(pos, l, t) -> CropDragMode.TopLeft
                            near(pos, r, t) -> CropDragMode.TopRight
                            near(pos, r, b) -> CropDragMode.BottomRight
                            near(pos, l, b) -> CropDragMode.BottomLeft
                            pos.x in l..r && pos.y in t..b -> CropDragMode.Move
                            else -> CropDragMode.None
                        }
                        dragStart = state
                    },
                    onDrag = { change, _ ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        change.consume()
                        val (nx, ny) = change.position
                        val fx = (nx / w).coerceIn(0f, 1f)
                        val fy = (ny / h).coerceIn(0f, 1f)
                        val s = dragStart
                        when (mode) {
                            CropDragMode.TopLeft -> {
                                state = CropRect(
                                    min(fx, state.r), min(fy, state.b),
                                    state.r, state.b,
                                )
                            }
                            CropDragMode.TopRight -> {
                                state = CropRect(state.l, min(fy, state.b), max(fx, state.l), state.b)
                            }
                            CropDragMode.BottomRight -> {
                                state = CropRect(state.l, state.t, max(fx, state.l), max(fy, state.t))
                            }
                            CropDragMode.BottomLeft -> {
                                state = CropRect(min(fx, state.r), state.t, state.r, max(fy, state.t))
                            }
                            CropDragMode.Move -> {
                                val dw = fx - s.l
                                val dh = fy - s.t
                                val nl = (s.l + dw).coerceIn(0f, 1f)
                                val nt = (s.t + dh).coerceIn(0f, 1f)
                                state = CropRect(
                                    nl, nt,
                                    (nl + (s.r - s.l)).coerceAtMost(1f),
                                    (nt + (s.b - s.t)).coerceAtMost(1f),
                                )
                            }
                            CropDragMode.None -> {}
                        }
                        onCropChange(state)
                    },
                    onDragEnd = { mode = CropDragMode.None },
                    onDragCancel = { mode = CropDragMode.None },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        if (w == 0f || h == 0f) return@Canvas

        val l = state.l * w
        val t = state.t * h
        val r = state.r * w
        val b = state.b * h
        val dim = Color.Black.copy(alpha = 0.5f)
        val accent = Color(0xFF4A5FC1)

        // Dim the four regions outside the crop box (zero-area when full-image).
        drawRect(dim, topLeft = Offset(0f, 0f), size = Size(w, t))
        drawRect(dim, topLeft = Offset(0f, b), size = Size(w, (h - b).coerceAtLeast(0f)))
        drawRect(dim, topLeft = Offset(0f, t), size = Size(l.coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))
        drawRect(dim, topLeft = Offset(r, t), size = Size((w - r).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))

        // Crop box border.
        drawRect(
            color = Color.White,
            topLeft = Offset(l, t),
            size = Size(r - l, b - t),
            style = Stroke(width = 2.dp.toPx()),
        )
        // Rule-of-thirds grid.
        val tw = (r - l) / 3f
        val th = (b - t) / 3f
        for (i in 1..2) {
            drawLine(Color.White.copy(alpha = 0.5f), Offset(l + tw * i, t), Offset(l + tw * i, b), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.5f), Offset(l, t + th * i), Offset(r, t + th * i), 1.dp.toPx())
        }
        // Corner handles.
        val hs = HANDLE
        drawRect(accent, topLeft = Offset(l - hs / 2, t - hs / 2), size = Size(hs, hs))
        drawRect(accent, topLeft = Offset(r - hs / 2, t - hs / 2), size = Size(hs, hs))
        drawRect(accent, topLeft = Offset(l - hs / 2, b - hs / 2), size = Size(hs, hs))
        drawRect(accent, topLeft = Offset(r - hs / 2, b - hs / 2), size = Size(hs, hs))
    }
}

private sealed interface CropDragMode {
    data object None : CropDragMode
    data object TopLeft : CropDragMode
    data object TopRight : CropDragMode
    data object BottomLeft : CropDragMode
    data object BottomRight : CropDragMode
    data object Move : CropDragMode
}

private fun near(p: Offset, x: Float, y: Float): Boolean =
    kotlin.math.abs(p.x - x) < HANDLE && kotlin.math.abs(p.y - y) < HANDLE

@Composable
private fun EditControls(
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onTiltLeft: () -> Unit,
    onTiltRight: () -> Unit,
    onApplyCrop: () -> Unit,
    onResetCrop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onRotateLeft) {
                    Icon(Icons.Filled.RotateLeft, contentDescription = stringResource(R.string.rotate_left))
                }
                IconButton(onClick = onTiltLeft) {
                    Text("-5°", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = onTiltRight) {
                    Text("+5°", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = onRotateRight) {
                    Icon(Icons.Filled.RotateRight, contentDescription = stringResource(R.string.rotate_right))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onResetCrop, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Crop, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.crop_reset))
                }
                Button(onClick = onApplyCrop, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.apply_crop))
                }
            }
        }
    }
}

@Composable
private fun EditBottomBar(onCancel: () -> Unit, onAccept: () -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.cancel))
        }
        Button(onClick = onAccept, enabled = enabled, modifier = Modifier.weight(1.6f)) {
            Text(stringResource(R.string.scan_and_review))
        }
    }
}

@Composable
private fun EditProcessingOverlay(stepLabel: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.processing_title), style = MaterialTheme.typography.titleMedium)
                stepLabel?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
