package com.krishiradar.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_IMAGE_PX = 512

@Singleton
class InferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "InferenceEngine"
    private val loadMutex = Mutex()
    private val generationMutex = Mutex()

    @Volatile private var engine: Engine? = null

    private val _isLoadedFlow = MutableStateFlow(false)
    val isLoadedFlow: StateFlow<Boolean> = _isLoadedFlow.asStateFlow()

    private val _loadErrorFlow = MutableStateFlow<String?>(null)
    val loadErrorFlow: StateFlow<String?> = _loadErrorFlow.asStateFlow()

    @Volatile var isLoaded: Boolean = false
        private set

    @Volatile var loadedModelPath: String? = null
        private set

    @Volatile var visionEnabled: Boolean = false
        private set

    @Volatile var gpuEnabled: Boolean = false
        private set

    suspend fun load(modelPath: String) {
        loadMutex.withLock {
            if (isLoaded && loadedModelPath == modelPath) return
            _loadErrorFlow.value = null
            releaseEngine()

            val file = File(modelPath)
            if (!file.exists()) {
                val msg = "Model file not found: $modelPath"
                _loadErrorFlow.value = msg
                throw IllegalStateException(msg)
            }

            // Delete stale XNNPack cache files — an incompatible cache from a previous LiteRT-LM
            // version causes a deterministic SIGSEGV in liblitertlm_jni.so during initialize().
            file.parentFile?.listFiles { f ->
                f.name.startsWith(file.name + ".xnnpack_cache_")
            }?.forEach {
                Log.i(tag, "Deleting stale XNNPack cache: ${it.name}")
                it.delete()
            }

            Log.i(tag, "Loading model: $modelPath")
            try {
                // The "vision encoder must have exactly one signature" error can come from either
                // Engine() constructor or initialize() — so both must be inside the same try block.
                // Gallery source: do NOT pass maxNumImages; control vision via visionBackend only.
                val (eng, hasVision, hasGpu) = loadEngineWithFallback(modelPath)
                engine = eng
                isLoaded = true
                visionEnabled = hasVision
                gpuEnabled = hasGpu
                _isLoadedFlow.value = true
                loadedModelPath = modelPath
                Log.i(tag, "Model loaded (vision=$hasVision, gpu=$hasGpu)")
            } catch (e: Exception) {
                _loadErrorFlow.value = e.message ?: "Model failed to initialize"
                Log.e(tag, "Model load failed", e)
                throw e
            }
        }
    }

    // GPU crash guard: GPU backends can trigger native SIGABRT/SIGSEGV in the LiteRT-LM JNI layer
    // on certain devices (e.g. Adreno 710 / WebGPU validation errors). Native signals cannot be
    // caught by Kotlin try-catch, so the fallback loop never runs. The guard writes a flag with
    // commit() (synchronous — survives the crash) before any GPU attempt and clears it on success
    // or on a caught Java exception. If the flag is still set on the next launch, the process died
    // during a GPU attempt and we skip all GPU backends for this session.
    private val prefs = context.getSharedPreferences("inference_engine", Context.MODE_PRIVATE)
    private fun setGpuCrashGuard() = prefs.edit().putBoolean("gpu_crash", true).commit()
    private fun clearGpuCrashGuard() = prefs.edit().putBoolean("gpu_crash", false).commit()
    private fun isGpuBlacklisted() = prefs.getBoolean("gpu_crash", false)

    // Tries configurations from fastest to most compatible. GPU main backend gives 2-4x faster
    // token generation on modern Android devices (Adreno, Mali, Immortalis).
    // NEVER calls close() on a failed engine — doing so corrupts the global LiteRT-LM native
    // state and causes all subsequent Engine() calls to fail.
    // Returns Triple(engine, hasVision, hasGpu)
    private suspend fun loadEngineWithFallback(modelPath: String): Triple<Engine, Boolean, Boolean> {
        data class Attempt(val main: Backend, val vision: Backend?, val hasVision: Boolean, val usesGpu: Boolean)
        val allAttempts = listOf(
            Attempt(Backend.GPU(), Backend.GPU(), true,  true),
            Attempt(Backend.GPU(), Backend.CPU(), true,  true),
            Attempt(Backend.CPU(), Backend.GPU(), true,  true),
            Attempt(Backend.CPU(), Backend.CPU(), true,  false),
            Attempt(Backend.GPU(), null,           false, true),
            Attempt(Backend.CPU(), null,           false, false),
        )
        val gpuBlacklisted = isGpuBlacklisted()
        if (gpuBlacklisted) Log.w(tag, "GPU blacklisted (previous native crash) — using CPU only")
        val attempts = if (gpuBlacklisted) allAttempts.filter { !it.usesGpu } else allAttempts

        for (attempt in attempts) {
            try {
                if (attempt.usesGpu) setGpuCrashGuard()
                val eng = Engine(EngineConfig(
                    modelPath = modelPath,
                    backend = attempt.main,
                    visionBackend = attempt.vision
                ))
                withContext(Dispatchers.IO) { eng.initialize() }
                clearGpuCrashGuard()
                val isGpu = attempt.main is Backend.GPU
                Log.i(tag, "Engine loaded: main=${attempt.main}, vision=${attempt.vision}")
                return Triple(eng, attempt.hasVision, isGpu)
            } catch (e: Exception) {
                if (attempt.usesGpu) clearGpuCrashGuard()
                Log.w(tag, "Attempt failed (main=${attempt.main}, vision=${attempt.vision}): ${e.message}")
            }
        }
        throw IllegalStateException("All engine load attempts failed for: $modelPath")
    }

    fun generateStream(prompt: String, imageBitmap: Bitmap? = null): Flow<String> = flow {
        check(isLoaded) { "Engine not loaded" }
        val eng = checkNotNull(engine) { "Engine is null" }

        if (!generationMutex.tryLock()) {
            throw IllegalStateException("Engine is busy — another generation is in progress")
        }
        // If model loaded without vision support, strip the image so we fall through to text-only path
        val effectiveBitmap = if (visionEnabled) imageBitmap else null
        val effectivePrompt = if (!visionEnabled && imageBitmap != null)
            prompt.replace("<image>", "").trim() else prompt

        Log.d(tag, "Generating (${effectivePrompt.length} chars, vision=${effectiveBitmap != null})")
        try {
            eng.createConversation().use { conversation ->
                if (effectiveBitmap != null) {
                    val bytes = bitmapToPngBytes(scaledDown(effectiveBitmap))
                    conversation.sendMessageAsync(
                        Contents.of(Content.ImageBytes(bytes), Content.Text(effectivePrompt))
                    ).collect { msg ->
                        val text = msg.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        if (text.isNotEmpty()) emit(text)
                    }
                } else {
                    conversation.sendMessageAsync(effectivePrompt).collect { msg ->
                        val text = msg.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        if (text.isNotEmpty()) emit(text)
                    }
                }
            }
        } finally {
            generationMutex.unlock()
        }
    }.flowOn(Dispatchers.IO)

    fun generate(prompt: String, imageBitmap: Bitmap? = null): Flow<String> =
        generateStream(prompt, imageBitmap)

    fun unload() {
        if (!isLoaded) return
        // Defer unload if a generation is still running — closing the engine mid-stream
        // causes undefined behaviour in the native LiteRT-LM library.
        if (generationMutex.isLocked) {
            Log.w(tag, "Unload requested but generation is active — skipping")
            return
        }
        Log.i(tag, "Unloading model")
        releaseEngine()
    }

    private fun releaseEngine() {
        engine?.close()
        engine = null
        isLoaded = false
        visionEnabled = false
        gpuEnabled = false
        _isLoadedFlow.value = false
        _loadErrorFlow.value = null
        loadedModelPath = null
    }

    private fun scaledDown(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= MAX_IMAGE_PX && h <= MAX_IMAGE_PX) return bitmap
        val scale = MAX_IMAGE_PX.toFloat() / maxOf(w, h)
        val scaled = Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
        // Recycle the original so only the smaller copy lives in memory
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val soft = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else bitmap
        return ByteArrayOutputStream().also { soft.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
