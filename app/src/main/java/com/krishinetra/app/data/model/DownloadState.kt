package com.krishiradar.app.data.model

sealed class DownloadState {
    data object Idle : DownloadState()
    data object Queued : DownloadState()
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long = 0L
    ) : DownloadState() {
        val progress: Float
            get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
        val progressPercent: Int
            get() = (progress * 100).toInt()
        val remainingBytes: Long
            get() = totalBytes - bytesDownloaded
        val etaSeconds: Long
            get() = if (speedBytesPerSec > 0) remainingBytes / speedBytesPerSec else -1L
    }
    data object Verifying : DownloadState()
    data object Completed : DownloadState()
    data class Failed(val reason: String, val isResumable: Boolean = true) : DownloadState()
    data object Paused : DownloadState()
    data object Deleting : DownloadState()
}

data class ModelDownloadInfo(
    val variant: ModelVariant,
    val state: DownloadState,
    val localPath: String? = null,
    val workRequestId: String? = null
)
