package com.krishiradar.app.inference

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.krishiradar.app.data.repository.ModelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the lifecycle of [InferenceEngine].
 * Hooks into Android memory callbacks to release the model when the system is under pressure.
 */
@Singleton
class InferenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val engine: InferenceEngine,
    private val modelRepository: ModelRepository
) : ComponentCallbacks2 {

    private val tag = "InferenceManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Load the active model. No-op if already loaded with the same file. Reloads on model switch. */
    fun ensureLoaded() {
        scope.launch {
            val path = modelRepository.getActiveModelPath() ?: return@launch
            if (engine.isLoaded && engine.loadedModelPath == path) return@launch
            try {
                engine.load(path)
            } catch (e: Exception) {
                Log.e(tag, "Failed to load model", e)
                // Error is already stored in engine.loadErrorFlow for UI consumption
            }
        }
    }

    /** Pre-warm model on app open. */
    fun preWarmIfAppropriate() {
        scope.launch {
            val path = modelRepository.getActiveModelPath() ?: return@launch
            if (!engine.isLoaded) {
                try {
                    engine.load(path)
                    Log.i(tag, "Model pre-warmed")
                } catch (e: Exception) {
                    Log.w(tag, "Pre-warm skipped: ${e.message}")
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        // Only release on genuine memory pressure. TRIM_MEMORY_UI_HIDDEN and
        // TRIM_MEMORY_BACKGROUND fire on every app switch, which would force a
        // 5–15 s model reload every time the user returns — avoid that.
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.w(tag, "onTrimMemory($level) — memory pressure, releasing model")
                engine.unload()
            }
            else -> Unit
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
    override fun onLowMemory() {
        Log.w(tag, "onLowMemory — forcing model unload")
        engine.unload()
    }
}
