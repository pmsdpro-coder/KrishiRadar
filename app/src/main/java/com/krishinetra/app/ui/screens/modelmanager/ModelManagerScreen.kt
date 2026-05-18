package com.krishiradar.app.ui.screens.modelmanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.krishiradar.app.data.model.DownloadState
import com.krishiradar.app.data.model.ModelDownloadInfo
import com.krishiradar.app.data.model.ModelVariant
import com.krishiradar.app.ui.viewmodel.ModelManagerViewModel

@Composable
fun ModelManagerScreen(
    onBack: () -> Unit,
    onViewCapability: () -> Unit,
    viewModel: ModelManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Manager") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onViewCapability) {
                        Icon(Icons.Default.Devices, contentDescription = "Device capability")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Recommendation banner
            uiState.recommendation?.let { rec ->
                item { RecommendationBanner(recommendation = rec, onDownload = { viewModel.requestDownload(rec.variant) }) }
            }

            // WiFi-only toggle
            item { WifiOnlyToggle(checked = uiState.wifiOnly, onCheckedChange = viewModel::setWifiOnly) }

            item {
                Text("Available Models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            items(uiState.models, key = { it.variant.id }) { info ->
                ModelCard(
                    info = info,
                    isActive = info.variant.id == uiState.activeModelId,
                    isRecommended = info.variant.id == uiState.recommendation?.variant?.id,
                    onDownload = { viewModel.requestDownload(info.variant) },
                    onCancel = { viewModel.cancelDownload(info.variant) },
                    onDelete = { viewModel.deleteModel(info.variant) },
                    onSelect = { viewModel.selectModel(info.variant) }
                )
            }
        }
    }

    // Cellular data confirmation dialog
    uiState.showCellularDialog?.let { variant ->
        AlertDialog(
            onDismissRequest = { viewModel.confirmCellularDownload(false) },
            icon = { Icon(Icons.Default.SignalCellularAlt, contentDescription = null) },
            title = { Text("Download on mobile data?") },
            text = { Text("This model is ${variant.displaySize}. Downloading on mobile data may incur significant charges.") },
            confirmButton = { Button(onClick = { viewModel.confirmCellularDownload(true) }) { Text("Download anyway") } },
            dismissButton = { TextButton(onClick = { viewModel.confirmCellularDownload(false) }) { Text("Cancel") } }
        )
    }

    // Hardware warning dialog
    uiState.showHardwareWarning?.let { variant ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissHardwareWarning(false) },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Device may struggle") },
            text = {
                Column {
                    Text("Your device may not have enough resources for ${variant.displayName}.")
                    uiState.deviceCapability?.let { cap ->
                        Spacer(Modifier.height(8.dp))
                        Text("• Device RAM: ${"%.1f".format(cap.totalRamGb)} GB (need ${variant.minRamBytes / 1_073_741_824}GB+)", style = MaterialTheme.typography.bodySmall)
                        Text("• Free storage: ${"%.1f".format(cap.freeStorageGb)} GB (need ${variant.minStorageBytes / 1_073_741_824}GB+)", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Performance may be degraded or the app may crash.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissHardwareWarning(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Continue anyway")
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.dismissHardwareWarning(false) }) { Text("Cancel") } }
        )
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
private fun RecommendationBanner(
    recommendation: com.krishiradar.app.data.model.ModelRecommendation,
    onDownload: () -> Unit
) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Recommend, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Recommended: ${recommendation.variant.displayName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(recommendation.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                recommendation.warningMessage?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun WifiOnlyToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Wi-Fi only", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("Download only on Wi-Fi (recommended for large models)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun ModelCard(
    info: ModelDownloadInfo,
    isActive: Boolean,
    isRecommended: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = if (isActive) 4.dp else 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(info.variant.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (isRecommended) { Spacer(Modifier.width(6.dp)); Badge { Text("Best fit") } }
                        if (isActive) { Spacer(Modifier.width(6.dp)); Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("Active") } }
                    }
                    Text(info.variant.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpecChip("${info.variant.parameterCount} params")
                SpecChip(info.variant.displaySize)
                SpecChip("Vision")
            }

            Spacer(Modifier.height(12.dp))

            when (val state = info.state) {
                is DownloadState.Idle -> {
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Download")
                    }
                }
                is DownloadState.Queued -> {
                    Text("Queued…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
                is DownloadState.Downloading -> {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${state.progressPercent}%  ${formatBytes(state.bytesDownloaded)}", style = MaterialTheme.typography.bodySmall)
                            Text("/ ${formatBytes(state.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        if (state.etaSeconds > 0) {
                            Text("ETA: ${formatEta(state.etaSeconds)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
                is DownloadState.Verifying -> {
                    Text("Verifying…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                is DownloadState.Failed -> {
                    val friendlyError = humaniseError(state.reason)
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(friendlyError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Retry")
                    }
                }
                is DownloadState.Completed -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isActive) {
                            Button(onClick = onSelect, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp)); Text("Use")
                            }
                        } else {
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.weight(1f)) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Active", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        OutlinedButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                is DownloadState.Paused -> {
                    Text("Paused — will resume on reconnect", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Resume") }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun SpecChip(label: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private fun humaniseError(raw: String): String = when {
    raw.contains("401") || raw.contains("403") ->
        "Access denied (HTTP ${if (raw.contains("401")) "401" else "403"}). Check that the model URL is still valid."
    raw.contains("404") ->
        "Model file not found (HTTP 404). The model URL may have changed."
    raw.contains("checksum") ->
        "File corrupted during download. Tap Retry to re-download."
    raw.contains("max_retries") ->
        "Download failed after 5 attempts. Check your internet connection and retry."
    else -> "Download failed: $raw"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000     -> "%.0f MB".format(bytes / 1e6)
    bytes >= 1_000         -> "%.0f KB".format(bytes / 1e3)
    else                   -> "$bytes B"
}

private fun formatEta(s: Long): String {
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m ${sec}s" else "${sec}s"
}
