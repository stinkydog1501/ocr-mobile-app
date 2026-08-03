package com.kinonn.ocrmobile.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kinonn.ocrmobile.R
import com.kinonn.ocrmobile.ui.theme.ConfidenceHigh
import com.kinonn.ocrmobile.ui.theme.ConfidenceMedium
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onRetake: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ReviewEvent.Copied -> snackbarHostState.showSnackbar(
                    context.getString(R.string.copied_to_clipboard)
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_title)) },
                navigationIcon = {
                    IconButton(onClick = onRetake) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            ReviewBottomBar(
                onRetake = onRetake,
                onCopy = { viewModel.copyJson(context) },
                onShare = { viewModel.share(context) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryHeader(uiState)

            if (uiState.needsReview) {
                NeedsReviewBanner()
            }

            uiState.fields.forEach { field ->
                FieldCard(field = field, onValueChange = { viewModel.updateFieldValue(field.key, it) })
            }

            RawTextCard(rawText = uiState.rawText)
        }
    }
}

@Composable
private fun SummaryHeader(uiState: ReviewUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text(uiState.documentType) },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${(uiState.overallConfidence * 100).toInt()}% confidence",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (uiState.overallConfidence >= 0.7f) ConfidenceHigh else ConfidenceMedium,
                )
            }
            LinearProgressIndicator(
                progress = { uiState.overallConfidence.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NeedsReviewBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.needs_review),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun FieldCard(field: FieldUi, onValueChange: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                ConfidenceBadge(confidence = field.confidence, needsManualEntry = field.needsManualEntry)
            }
            OutlinedTextField(
                value = field.value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = field.key != "address" && field.key != "full_text",
                isError = field.needsManualEntry,
                supportingText = if (field.needsManualEntry) {
                    { Text(stringResource(R.string.check_this_value)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun ConfidenceBadge(confidence: Float, needsManualEntry: Boolean) {
    val color = when {
        needsManualEntry || confidence < 0.55f -> MaterialTheme.colorScheme.error
        confidence < 0.7f -> ConfidenceMedium
        else -> ConfidenceHigh
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = "${(confidence * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun RawTextCard(rawText: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    stringResource(
                        if (expanded) R.string.hide_raw else R.string.show_raw
                    )
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = rawText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ReviewBottomBar(
    onRetake: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRetake) {
                Text(stringResource(R.string.retake))
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = onCopy) {
                Text(stringResource(R.string.copy_json))
            }
            Button(onClick = onShare) {
                Text(stringResource(R.string.share))
            }
        }
    }
}
