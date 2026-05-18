package com.krishiradar.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.*
import com.krishiradar.app.data.model.DeviceCapability
import com.krishiradar.app.data.model.DownloadState
import com.krishiradar.app.data.model.ModelDownloadInfo
import com.krishiradar.app.data.model.ModelRecommendation
import com.krishiradar.app.data.model.ModelVariant
import com.krishiradar.app.worker.ModelDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "model_prefs")

@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)
    private val activeModelKey = stringPreferencesKey("active_model_id")

    val modelInfoFlow: Flow<List<ModelDownloadInfo>> =
        combine(ModelVariant.entries.map { variant -> variantStateFlow(variant) }) { it.toList() }

    private fun variantStateFlow(variant: ModelVariant): Flow<ModelDownloadInfo> {
        val initial = buildInfo(variant)
        return workManager
            .getWorkInfosByTagFlow(variant.id)
            .map { workInfos -> mapWorkState(variant, workInfos, initial) }
            .onStart { emit(initial) }
            .distinctUntilChanged()
    }

    private fun mapWorkState(
        variant: ModelVariant,
        workInfos: List<WorkInfo>,
        fallback: ModelDownloadInfo
    ): ModelDownloadInfo {
        // Prefer active/terminal-success states over stale cancelled entries.
        // WorkManager retains old cancelled works (from ExistingWorkPolicy.REPLACE) in the DB,
        // so firstOrNull() without sorting can return a stale CANCELLED record.
        val work = workInfos.minByOrNull {
            when (it.state) {
                WorkInfo.State.RUNNING   -> 0
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED   -> 1
                WorkInfo.State.SUCCEEDED -> 2
                WorkInfo.State.FAILED    -> 3
                WorkInfo.State.CANCELLED -> 4
            }
        } ?: return buildInfo(variant)

        val state: DownloadState = when (work.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED  -> DownloadState.Queued

            WorkInfo.State.RUNNING  -> {
                val downloaded = work.progress.getLong(ModelDownloadWorker.PROGRESS_BYTES_DOWNLOADED, 0L)
                val total      = work.progress.getLong(ModelDownloadWorker.PROGRESS_TOTAL_BYTES, 0L)
                val phase      = work.progress.getString(ModelDownloadWorker.PROGRESS_PHASE)
                if (phase == ModelDownloadWorker.PHASE_VERIFYING) DownloadState.Verifying
                else DownloadState.Downloading(downloaded, total)
            }

            WorkInfo.State.SUCCEEDED -> {
                val path = work.outputData.getString(ModelDownloadWorker.OUTPUT_FILE_PATH)
                if (path != null && File(path).exists()) {
                    DownloadState.Completed
                } else {
                    buildInfo(variant).state
                }
            }

            WorkInfo.State.FAILED -> {
                val err = work.outputData.getString(ModelDownloadWorker.OUTPUT_ERROR) ?: "unknown"
                DownloadState.Failed(err, isResumable = !err.contains("permanent"))
            }

            WorkInfo.State.CANCELLED -> buildInfo(variant).state
        }

        val file = modelFile(variant)
        return ModelDownloadInfo(
            variant = variant,
            state = state,
            localPath = if (file.exists()) file.absolutePath else null
        )
    }

    private fun buildInfo(variant: ModelVariant): ModelDownloadInfo {
        val file = modelFile(variant)
        return ModelDownloadInfo(
            variant = variant,
            state = if (file.exists()) DownloadState.Completed else DownloadState.Idle,
            localPath = if (file.exists()) file.absolutePath else null
        )
    }

    fun modelFile(variant: ModelVariant): File =
        File(context.filesDir, "models/${variant.filename}")

    suspend fun startDownload(variant: ModelVariant, allowCellular: Boolean = false): String {
        val request = if (allowCellular)
            ModelDownloadWorker.buildRequestAllowCellular(variant)
        else
            ModelDownloadWorker.buildRequest(variant)

        workManager.enqueueUniqueWork(
            variant.id,
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id.toString()
    }

    fun cancelDownload(variant: ModelVariant) {
        workManager.cancelAllWorkByTag(variant.id)
    }

    fun deleteModel(variant: ModelVariant) {
        cancelDownload(variant)
        modelFile(variant).delete()
        File(context.filesDir, "models/${variant.filename}.tmp").delete()
    }

    suspend fun setActiveModel(variantId: String) {
        context.dataStore.edit { it[activeModelKey] = variantId }
    }

    suspend fun getActiveModelId(): String? =
        context.dataStore.data.map { it[activeModelKey] }.firstOrNull()

    suspend fun getActiveModelPath(): String? {
        val id = getActiveModelId() ?: return null
        val variant = ModelVariant.entries.find { it.id == id } ?: return null
        val file = modelFile(variant)
        return if (file.exists()) file.absolutePath else null
    }

    val activeModelIdFlow: Flow<String?> =
        context.dataStore.data.map { it[activeModelKey] }

    fun recommend(cap: DeviceCapability): ModelRecommendation {
        return when (cap.tier) {
            DeviceCapability.DeviceTier.HIGH -> ModelRecommendation(
                variant = ModelVariant.GEMMA4_E4B,
                reason = "Your device has ${cap.totalRamGb.f1} GB RAM — best quality model selected.",
                adequateHardware = true
            )
            DeviceCapability.DeviceTier.MID -> ModelRecommendation(
                variant = ModelVariant.GEMMA4_E2B,
                reason = "Balanced model for your ${cap.totalRamGb.f1} GB RAM device.",
                adequateHardware = cap.freeStorageBytes >= ModelVariant.GEMMA4_E2B.minStorageBytes,
                warningMessage = if (cap.freeStorageBytes < ModelVariant.GEMMA4_E2B.minStorageBytes)
                    "Need ${ModelVariant.GEMMA4_E2B.displaySize} free storage (have ${cap.freeStorageGb.f1} GB)" else null
            )
            DeviceCapability.DeviceTier.LOW -> {
                val ok = cap.freeStorageBytes >= ModelVariant.GEMMA4_E2B.minStorageBytes &&
                         cap.totalRamBytes >= ModelVariant.GEMMA4_E2B.minRamBytes
                ModelRecommendation(
                    variant = ModelVariant.GEMMA4_E2B,
                    reason = "Lightest model for your ${cap.totalRamGb.f1} GB RAM device.",
                    adequateHardware = ok,
                    warningMessage = if (!ok) "Device may struggle. Performance could be degraded." else null
                )
            }
            DeviceCapability.DeviceTier.INSUFFICIENT -> ModelRecommendation(
                variant = ModelVariant.GEMMA4_E2B,
                reason = "Device has limited resources.",
                adequateHardware = false,
                warningMessage = "Your device (${cap.totalRamGb.f1} GB RAM) may not support on-device AI well."
            )
        }
    }

    private val Float.f1: String get() = "%.1f".format(this)
}
