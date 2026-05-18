package com.krishiradar.app.ui.screens.diagnosis

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.krishiradar.app.inference.ClassificationResult
import com.krishiradar.app.inference.ConfidenceTier
import com.krishiradar.app.rag.PromptBuilder.ConversationTurn
import com.krishiradar.app.rag.RetrievedChunk
import com.krishiradar.app.ui.viewmodel.DiagnosisLanguage
import com.krishiradar.app.ui.viewmodel.DiagnosisPhase
import com.krishiradar.app.ui.viewmodel.DiagnosisViewModel
import java.io.File

@Composable
fun DiagnosisScreen(
    onBack: () -> Unit,
    onNavigateToModelManager: () -> Unit = {},
    viewModel: DiagnosisViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.setImageUri(it) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let { viewModel.setImageUri(it) }
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val file = File(context.cacheDir, "diag_capture.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
    }
    val audioPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording()
    }

    // Auto-scroll to bottom when new content appears
    val historySize = uiState.conversationHistory.size
    val isStreaming = uiState.streamingResponse.isNotBlank()
    LaunchedEffect(historySize, isStreaming) {
        if (historySize > 0 || isStreaming) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Coco", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleSpeaker() }) {
                        Icon(
                            if (uiState.isSpeakerEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = "Toggle speaker",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    IconButton(onClick = onNavigateToModelManager) {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = "Model Manager",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Language selector ──────────────────────────────────────────────
            val langChunks = DiagnosisLanguage.entries.chunked(3)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                langChunks.forEach { rowLangs ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowLangs.forEach { lang ->
                            val selected = uiState.selectedLanguage == lang
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.selectLanguage(lang) },
                                label = {
                                    Text(lang.displayName, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - rowLangs.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            // ── RAG status warning ─────────────────────────────────────────────
            if (!uiState.ragHasEmbedding) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp))
                        Text(
                            "Using keyword-search fallback for knowledge retrieval.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // ── Image area ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = if (uiState.capturedImage == null) 2.dp else 0.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.capturedImage != null) {
                    AsyncImage(
                        model = uiState.capturedImage,
                        contentDescription = "Captured plant image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Classification badge overlay (once classifier has run)
                    if (uiState.classificationDone) {
                        val result = uiState.classificationResult
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            if (result != null && result.confidence >= 0.9f) {
                                Text(
                                    "${result.displayLabel} · ${(result.confidence * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1E5C1E)
                                )
                            } else {
                                Text(
                                    "Sending to Gemma for analysis",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    // Clear button
                    if (uiState.phase == DiagnosisPhase.IDLE || uiState.phase == DiagnosisPhase.DONE) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                .clickable { viewModel.reset() }
                                .padding(6.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear image",
                                modifier = Modifier.size(16.dp))
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PhotoCamera, contentDescription = null,
                            modifier = Modifier.size(52.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Capture or pick a photo (optional)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center)
                    }
                }
            }

            // ── Gallery / Camera buttons ──────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Photo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Gallery")
                }
                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            val file = File(context.cacheDir, "diag_capture.jpg")
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            cameraUri = uri
                            cameraLauncher.launch(uri)
                        } else {
                            cameraPermLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Camera")
                }
            }

            // ── Query input ───────────────────────────────────────────────────
            OutlinedTextField(
                value = uiState.inputQuery,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ask a question") },
                placeholder = { Text("e.g. What should I spray on the tree?") },
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    IconButton(onClick = {
                        if (uiState.isRecording) {
                            viewModel.stopRecording()
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.startRecording()
                        } else {
                            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Icon(
                            if (uiState.isRecording) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (uiState.isRecording) "Stop" else "Voice input",
                            tint = if (uiState.isRecording) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )

            // ── Execute / Stop button ─────────────────────────────────────────
            val isRunning = uiState.phase == DiagnosisPhase.CLASSIFYING
                    || uiState.phase == DiagnosisPhase.RETRIEVING
                    || uiState.phase == DiagnosisPhase.GENERATING

            val canExecute = (uiState.capturedImage != null || uiState.inputQuery.isNotBlank())
                    && uiState.engineLoaded
                    && !isRunning

            Button(
                onClick = { if (isRunning) viewModel.stopGeneration() else viewModel.execute() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = canExecute || isRunning,
                colors = if (isRunning) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ) else ButtonDefaults.buttonColors()
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (uiState.phase) {
                            DiagnosisPhase.CLASSIFYING -> "Identifying disease…"
                            DiagnosisPhase.RETRIEVING  -> "Retrieving knowledge…"
                            DiagnosisPhase.GENERATING  -> "Generating response…"
                            else -> "Working…"
                        }
                    )
                } else {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Execute", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            // ── Model status ──────────────────────────────────────────────────
            when {
                !uiState.modelReady -> Text(
                    "No model loaded. Tap the chip icon (top right) to download a model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                !uiState.engineLoaded -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("Loading model into memory…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── Conversation history ──────────────────────────────────────────
            if (uiState.conversationHistory.isNotEmpty() || uiState.streamingResponse.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    uiState.conversationHistory.forEach { turn ->
                        if (turn.displayQuery.isNotBlank()) {
                            UserBubble(turn.displayQuery)
                        }
                        if (turn.assistantMessage.isNotBlank()) {
                            AssistantBubble(turn.assistantMessage, isStreaming = false)
                        }
                    }
                    // Streaming partial response
                    if (uiState.streamingResponse.isNotBlank()) {
                        AssistantBubble(uiState.streamingResponse, isStreaming = true)
                    }
                }
            }

            // ── Knowledge sources (from last execute) ─────────────────────────
            AnimatedVisibility(
                visible = uiState.retrievedChunks.isNotEmpty() && uiState.phase == DiagnosisPhase.DONE,
                enter = fadeIn() + expandVertically()
            ) {
                SourcesSection(uiState.retrievedChunks)
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (uiState.errorMessage != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("Error") },
            text = { Text(uiState.errorMessage!!) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } }
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun AssistantBubble(text: String, isStreaming: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Eco, contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isStreaming) {
                    Spacer(Modifier.height(4.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun SourcesSection(chunks: List<RetrievedChunk>) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MenuBook, contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Knowledge Sources (${chunks.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    chunks.forEachIndexed { i, rc ->
                        if (i > 0) Spacer(Modifier.height(8.dp))
                        Column {
                            Text(
                                "${rc.chunk.sourceName} · ${rc.chunk.section.replace('_', ' ')}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                rc.chunk.text.take(160) + if (rc.chunk.text.length > 160) "…" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}
