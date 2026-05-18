package com.krishiradar.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krishiradar.app.data.model.DeviceCapability
import com.krishiradar.app.data.model.DownloadState
import com.krishiradar.app.data.model.ModelDownloadInfo
import com.krishiradar.app.data.model.ModelRecommendation
import com.krishiradar.app.data.model.ModelVariant
import com.krishiradar.app.data.repository.ModelRepository
import com.krishiradar.app.utils.DeviceCapabilityDetector
import com.krishiradar.app.utils.NetworkUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelManagerUiState(
    val models: List<ModelDownloadInfo> = emptyList(),
    val deviceCapability: DeviceCapability? = null,
    val recommendation: ModelRecommendation? = null,
    val activeModelId: String? = null,
    val wifiOnly: Boolean = true,
    val showCellularDialog: ModelVariant? = null,
    val showHardwareWarning: ModelVariant? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val capabilityDetector: DeviceCapabilityDetector,
    private val networkUtils: NetworkUtils
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelManagerUiState())
    val uiState: StateFlow<ModelManagerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val cap = capabilityDetector.detect()
            _uiState.update { it.copy(deviceCapability = cap, recommendation = modelRepository.recommend(cap)) }
        }
        viewModelScope.launch {
            modelRepository.modelInfoFlow.collect { infos ->
                _uiState.update { it.copy(models = infos) }
                // Auto-select the first completed model when no active model is chosen
                val activeId = modelRepository.getActiveModelId()
                if (activeId.isNullOrEmpty()) {
                    infos.firstOrNull { it.state == DownloadState.Completed }?.let {
                        modelRepository.setActiveModel(it.variant.id)
                    }
                }
            }
        }
        viewModelScope.launch {
            modelRepository.activeModelIdFlow.collect { id ->
                _uiState.update { it.copy(activeModelId = id) }
            }
        }
    }

    fun requestDownload(variant: ModelVariant) {
        val cap = _uiState.value.deviceCapability
        if (cap != null) {
            val ok = cap.freeStorageBytes >= variant.minStorageBytes &&
                     cap.totalRamBytes >= variant.minRamBytes
            if (!ok) {
                _uiState.update { it.copy(showHardwareWarning = variant) }
                return
            }
        }
        attemptDownload(variant)
    }

    fun dismissHardwareWarning(proceed: Boolean) {
        val variant = _uiState.value.showHardwareWarning ?: return
        _uiState.update { it.copy(showHardwareWarning = null) }
        if (proceed) attemptDownload(variant)
    }

    private fun attemptDownload(variant: ModelVariant) {
        if (_uiState.value.wifiOnly && !networkUtils.isOnWifi()) {
            _uiState.update { it.copy(showCellularDialog = variant) }
            return
        }
        startDownload(variant, allowCellular = !_uiState.value.wifiOnly)
    }

    fun confirmCellularDownload(proceed: Boolean) {
        val variant = _uiState.value.showCellularDialog ?: return
        _uiState.update { it.copy(showCellularDialog = null) }
        if (proceed) startDownload(variant, allowCellular = true)
    }

    private fun startDownload(variant: ModelVariant, allowCellular: Boolean) {
        viewModelScope.launch {
            modelRepository.startDownload(variant, allowCellular)
        }
    }

    fun cancelDownload(variant: ModelVariant) = modelRepository.cancelDownload(variant)

    fun deleteModel(variant: ModelVariant) {
        viewModelScope.launch {
            modelRepository.deleteModel(variant)
            if (_uiState.value.activeModelId == variant.id) modelRepository.setActiveModel("")
        }
    }

    fun selectModel(variant: ModelVariant) {
        val info = _uiState.value.models.find { it.variant.id == variant.id }
        if (info?.state != DownloadState.Completed) return
        viewModelScope.launch { modelRepository.setActiveModel(variant.id) }
    }

    fun setWifiOnly(value: Boolean) = _uiState.update { it.copy(wifiOnly = value) }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }
}
