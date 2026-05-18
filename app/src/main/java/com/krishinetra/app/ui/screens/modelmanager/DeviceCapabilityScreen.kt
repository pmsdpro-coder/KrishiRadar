package com.krishiradar.app.ui.screens.modelmanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.krishiradar.app.data.model.DeviceCapability
import com.krishiradar.app.data.model.ModelVariant
import com.krishiradar.app.ui.viewmodel.ModelManagerViewModel

@Composable
fun DeviceCapabilityScreen(
    onBack: () -> Unit,
    viewModel: ModelManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val cap = uiState.deviceCapability

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Capability") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        if (cap == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TierBanner(tier = cap.tier)
            }

            item {
                Text("Hardware", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            item {
                CapabilityCard {
                    CapRow(Icons.Default.Memory, "Total RAM", "%.1f GB".format(cap.totalRamGb))
                    CapRow(Icons.Default.DataUsage, "Available RAM", "%.1f GB".format(cap.availableRamGb))
                    CapRow(Icons.Default.Storage, "Free Storage", "%.1f GB".format(cap.freeStorageGb))
                    CapRow(Icons.Default.DeveloperBoard, "CPU Cores", cap.cpuCores.toString())
                    CapRow(Icons.Default.Speed, "Architecture", if (cap.isArm64) "ARM64" else cap.abi.firstOrNull() ?: "Unknown")
                    CapRow(Icons.Default.Android, "Android", "API ${cap.androidVersion}")
                    CapRow(Icons.Default.GraphicEq, "GPU", cap.gpuVendor)
                    CapRow(Icons.Default.Memory, "NPU", if (cap.hasNpu) "Detected" else "Not detected")
                }
            }

            item {
                Text("Model Compatibility", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            item {
                CapabilityCard {
                    ModelVariant.entries.forEach { variant ->
                        val fits = cap.totalRamBytes >= variant.minRamBytes &&
                                   cap.freeStorageBytes >= variant.minStorageBytes
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (fits) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                contentDescription = null,
                                tint = if (fits) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(variant.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                                Text("Needs ${variant.minRamBytes / 1024 / 1024 / 1024}GB RAM, ${variant.displaySize} storage",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            if (variant.id == uiState.recommendation?.variant?.id) {
                                Badge { Text("Best fit") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TierBanner(tier: DeviceCapability.DeviceTier) {
    val (color, icon, title, subtitle) = when (tier) {
        DeviceCapability.DeviceTier.HIGH -> Quadruple(
            MaterialTheme.colorScheme.primaryContainer,
            Icons.Default.Star,
            "High-end device",
            "Supports Gemma 4 E4B — best quality model"
        )
        DeviceCapability.DeviceTier.MID -> Quadruple(
            MaterialTheme.colorScheme.secondaryContainer,
            Icons.Default.Devices,
            "Mid-range device",
            "Recommended: Gemma 4 E2B"
        )
        DeviceCapability.DeviceTier.LOW -> Quadruple(
            MaterialTheme.colorScheme.tertiaryContainer,
            Icons.Default.PhoneAndroid,
            "Entry-level device",
            "Recommended: Gemma 4 E2B (may be slow)"
        )
        DeviceCapability.DeviceTier.INSUFFICIENT -> Quadruple(
            MaterialTheme.colorScheme.errorContainer,
            Icons.Default.Warning,
            "Limited hardware",
            "On-device AI may not perform well"
        )
    }
    Surface(shape = RoundedCornerShape(12.dp), color = color, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CapabilityCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun CapRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary)
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
