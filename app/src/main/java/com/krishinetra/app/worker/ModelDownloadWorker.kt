package com.krishiradar.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.krishiradar.app.data.model.ModelVariant
import com.krishiradar.app.utils.ChecksumVerifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val okHttpClient: OkHttpClient,
    private val checksumVerifier: ChecksumVerifier
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_MODEL_ID          = "model_id"
        const val KEY_DOWNLOAD_URL      = "download_url"
        const val KEY_EXPECTED_CHECKSUM = "expected_checksum"
        const val KEY_FILENAME          = "filename"

        const val PROGRESS_BYTES_DOWNLOADED = "bytes_downloaded"
        const val PROGRESS_TOTAL_BYTES      = "total_bytes"
        const val PROGRESS_PHASE            = "phase"
        const val PHASE_DOWNLOADING         = "downloading"
        const val PHASE_VERIFYING           = "verifying"
        const val PHASE_DONE                = "done"

        const val OUTPUT_FILE_PATH = "file_path"
        const val OUTPUT_ERROR     = "error"

        private const val TAG             = "ModelDownloadWorker"
        private const val CHANNEL_ID      = "model_download"
        private const val NOTIFICATION_ID = 1001
        private const val TEMP_SUFFIX     = ".tmp"

        private val PERMANENT_FAILURE_CODES = setOf(400, 401, 403, 404, 410)

        fun buildRequest(variant: ModelVariant): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                // NOTE: NOT adding setRequiresStorageNotLow — it silently blocks on many devices.
                .build()

            return OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(buildData(variant))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(variant.id)
                .build()
        }

        fun buildRequestAllowCellular(variant: ModelVariant): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            return OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(buildData(variant))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(variant.id)
                .build()
        }

        private fun buildData(variant: ModelVariant) = workDataOf(
            KEY_MODEL_ID          to variant.id,
            KEY_DOWNLOAD_URL      to variant.downloadUrl,
            KEY_EXPECTED_CHECKSUM to variant.expectedChecksum,
            KEY_FILENAME          to variant.filename
        )
    }

    override suspend fun doWork(): Result {
        // Top-level catch-all: ensures any unexpected throwable produces a readable error
        // instead of WorkManager's blank "unknown" output.
        return try {
            doDownload()
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t   // must re-throw so WorkManager can cancel the coroutine correctly
        } catch (t: Throwable) {
            Log.e(TAG, "Uncaught error in doWork: ${t::class.simpleName}: ${t.message}", t)
            Result.failure(error("${t::class.simpleName}: ${t.message}"))
        }
    }

    private suspend fun doDownload(): Result {
        val url      = inputData.getString(KEY_DOWNLOAD_URL)      ?: return Result.failure(error("Missing URL"))
        val filename = inputData.getString(KEY_FILENAME)           ?: return Result.failure(error("Missing filename"))
        val checksum = inputData.getString(KEY_EXPECTED_CHECKSUM)  ?: ""

        createNotificationChannel()
        // setForeground can throw if notification permission is denied — don't let it abort the download
        try { setForeground(buildForegroundInfo(0, 0)) } catch (e: Exception) {
            Log.w(TAG, "setForeground failed (notification permission?): ${e.message}")
        }

        val modelsDir = File(applicationContext.filesDir, "models")
        modelsDir.mkdirs()

        val destFile = File(modelsDir, filename)
        val tempFile = File(modelsDir, "$filename$TEMP_SUFFIX")

        return try {
            downloadWithResume(url, tempFile)
            reportProgress(tempFile.length(), tempFile.length(), PHASE_VERIFYING)

            if (!checksumVerifier.verify(tempFile, checksum)) {
                tempFile.delete()
                return Result.failure(error("checksum_mismatch"))
            }

            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }

            reportProgress(destFile.length(), destFile.length(), PHASE_DONE)
            Log.i(TAG, "Download complete: ${destFile.absolutePath}")
            Result.success(workDataOf(OUTPUT_FILE_PATH to destFile.absolutePath))

        } catch (e: PermanentDownloadException) {
            Log.e(TAG, "Permanent failure: ${e.message}")
            tempFile.delete()
            Result.failure(error(e.message ?: "permanent_failure"))

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // don't swallow cancellation inside the inner catch

        } catch (e: Exception) {
            Log.e(TAG, "Transient failure on attempt $runAttemptCount: ${e.message}", e)
            if (runAttemptCount >= 4) {
                tempFile.delete()
                Result.failure(error(e.message ?: "max_retries_exceeded: ${e::class.simpleName}"))
            } else {
                Result.retry()
            }
        }
    }

    private suspend fun downloadWithResume(url: String, dest: File) {
        val existingBytes = if (dest.exists()) dest.length() else 0L
        Log.d(TAG, "Starting download: $url (resume from $existingBytes bytes)")

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = okHttpClient.newCall(requestBuilder.build()).execute()
        val code = response.code

        if (code in PERMANENT_FAILURE_CODES) {
            response.close()
            val hint = if (code == 401 || code == 403)
                "HTTP $code — access denied. Check that the model URL is correct."
            else
                "HTTP $code for $url"
            throw PermanentDownloadException(hint)
        }

        if (!response.isSuccessful && code != 206) {
            response.close()
            throw Exception("HTTP $code — server error, will retry")
        }

        val isResume = code == 206
        val totalBytes: Long = if (isResume) {
            response.header("Content-Range")
                ?.substringAfterLast('/')?.toLongOrNull()
                ?: (existingBytes + (response.body?.contentLength() ?: 0L))
        } else {
            response.body?.contentLength() ?: -1L
        }

        Log.d(TAG, "HTTP $code | total=$totalBytes | resume=$isResume")

        response.body?.use { body ->
            val fileOut = FileOutputStream(dest, isResume)
            var bytesWritten = if (isResume) existingBytes else 0L
            var lastReport = System.currentTimeMillis()

            try {
                val buffer = ByteArray(64 * 1024)   // 64 KB — better throughput for large files
                val input = body.byteStream()
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    fileOut.write(buffer, 0, read)
                    bytesWritten += read
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 500) {
                        reportProgress(bytesWritten, totalBytes, PHASE_DOWNLOADING)
                        lastReport = now
                    }
                }
            } finally {
                fileOut.flush()
                fileOut.close()
            }
        } ?: throw Exception("Empty response body")
    }

    private suspend fun reportProgress(downloaded: Long, total: Long, phase: String) {
        setProgress(workDataOf(
            PROGRESS_BYTES_DOWNLOADED to downloaded,
            PROGRESS_TOTAL_BYTES      to total,
            PROGRESS_PHASE            to phase
        ))
        val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
        try { setForeground(buildForegroundInfo(pct, total)) } catch (e: Exception) {
            Log.w(TAG, "setForeground update failed: ${e.message}")
        }
    }

    private fun buildForegroundInfo(progress: Int, totalBytes: Long): ForegroundInfo {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("KrishiNetra — Downloading model")
            .setContentText(if (totalBytes > 0) "$progress%" else "Starting…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, totalBytes <= 0)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Model Download", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Gemma 4 model download progress" }
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun error(msg: String) = workDataOf(OUTPUT_ERROR to msg)

    class PermanentDownloadException(message: String) : Exception(message)
}
