package com.krishiradar.app.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krishiradar.app.data.repository.ModelRepository
import com.krishiradar.app.inference.ClassificationResult
import com.krishiradar.app.inference.CoconutClassifier
import com.krishiradar.app.inference.InferenceManager
import com.krishiradar.app.rag.PromptBuilder
import com.krishiradar.app.rag.PromptBuilder.ConversationTurn
import com.krishiradar.app.rag.RagRetriever
import com.krishiradar.app.rag.RetrievedChunk
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

enum class DiagnosisLanguage(
    val displayName: String,
    val localeTag: String,
    val voiceLocale: Locale,
    val code: String
) {
    ENGLISH("English", "en-IN", Locale("en", "IN"), "en"),
    TAMIL("Tamil",    "ta-IN", Locale("ta", "IN"), "ta"),
    KANNADA("Kannada", "kn-IN", Locale("kn", "IN"), "kn"),
    TELUGU("Telugu",  "te-IN", Locale("te", "IN"), "te"),
    HINDI("Hindi",    "hi-IN", Locale("hi", "IN"), "hi"),
    MALAYALAM("Malayalam", "ml-IN", Locale("ml", "IN"), "ml")
}

enum class DiagnosisPhase { IDLE, CLASSIFYING, RETRIEVING, GENERATING, DONE, ERROR }

data class DiagnosisUiState(
    val selectedLanguage: DiagnosisLanguage = DiagnosisLanguage.ENGLISH,
    val capturedImage: Bitmap? = null,
    val inputQuery: String = "",
    val isRecording: Boolean = false,
    // Classification — runs once per image load
    val classificationResult: ClassificationResult? = null,
    val classificationDone: Boolean = false,
    // Conversation
    val conversationHistory: List<ConversationTurn> = emptyList(),
    val streamingResponse: String = "",
    val retrievedChunks: List<RetrievedChunk> = emptyList(),
    // Status
    val phase: DiagnosisPhase = DiagnosisPhase.IDLE,
    val ragReady: Boolean = false,
    val ragHasEmbedding: Boolean = false,
    val modelReady: Boolean = false,
    val engineLoaded: Boolean = false,
    val isSpeakerEnabled: Boolean = false,
    val errorMessage: String? = null
)

private const val NON_COCONUT_THRESHOLD = 0.25f

@HiltViewModel
class DiagnosisViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRepository: ModelRepository,
    private val inferenceManager: InferenceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosisUiState())
    val uiState: StateFlow<DiagnosisUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsActiveLocale: Locale? = null
    private var classifier: CoconutClassifier? = null
    @Volatile private var ragRetriever: RagRetriever? = null

    init {
        viewModelScope.launch {
            modelRepository.activeModelIdFlow.collect { _ ->
                val path = modelRepository.getActiveModelPath()
                val wasReady = _uiState.value.modelReady
                _uiState.update { it.copy(modelReady = path != null) }
                if (path != null && !wasReady) inferenceManager.ensureLoaded()
            }
        }
        viewModelScope.launch {
            inferenceManager.engine.isLoadedFlow.collect { loaded ->
                _uiState.update { it.copy(engineLoaded = loaded) }
            }
        }
        viewModelScope.launch {
            inferenceManager.engine.loadErrorFlow.collect { error ->
                if (error != null) _uiState.update { it.copy(errorMessage = "Failed to load model: $error") }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try { classifier = CoconutClassifier(context) } catch (_: Exception) {}
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rag = RagRetriever(context)
                rag.initialize()
                ragRetriever = rag
                _uiState.update { it.copy(ragReady = true, ragHasEmbedding = rag.isEmbeddingAvailable()) }
            } catch (_: Exception) {
                _uiState.update { it.copy(ragReady = false) }
            }
        }
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = Unit
                    @Deprecated("Deprecated in API 21")
                    override fun onError(utteranceId: String?) = Unit
                    override fun onError(utteranceId: String?, errorCode: Int) = Unit
                })
            }
        }
    }

    fun selectLanguage(language: DiagnosisLanguage) {
        _uiState.update { it.copy(selectedLanguage = language) }
    }

    fun setImageUri(uri: Uri) {
        viewModelScope.launch {
            val bmp = uriToBitmap(uri)
            // New image resets classification and conversation
            _uiState.update {
                it.copy(
                    capturedImage = bmp,
                    phase = DiagnosisPhase.IDLE,
                    classificationResult = null,
                    classificationDone = false,
                    conversationHistory = emptyList(),
                    streamingResponse = "",
                    retrievedChunks = emptyList()
                )
            }
        }
    }

    fun onQueryChange(text: String) {
        _uiState.update { it.copy(inputQuery = text) }
    }

    fun toggleSpeaker() {
        val enabled = !_uiState.value.isSpeakerEnabled
        if (!enabled) tts?.stop()
        _uiState.update { it.copy(isSpeakerEnabled = enabled) }
    }

    fun execute() {
        val state = _uiState.value
        if (state.phase == DiagnosisPhase.CLASSIFYING
            || state.phase == DiagnosisPhase.RETRIEVING
            || state.phase == DiagnosisPhase.GENERATING) return

        val lang = state.selectedLanguage
        val hasImage = state.capturedImage != null
        val rawQuery = state.inputQuery.trim()

        // Need at least an image or a typed/voiced query
        val query = if (rawQuery.isNotBlank()) rawQuery
                    else if (hasImage) PromptBuilder.defaultQuery(lang.code)
                    else return

        // Clear input immediately so user sees it was consumed
        _uiState.update { it.copy(inputQuery = "", streamingResponse = "", errorMessage = null) }

        generationJob?.cancel()
        generationJob = viewModelScope.launch {

            // ── Step 1: Classify — only once per image load ───────────────────
            val classResult: ClassificationResult? = if (hasImage && !state.classificationDone) {
                _uiState.update { it.copy(phase = DiagnosisPhase.CLASSIFYING) }
                val result = try {
                    withContext(Dispatchers.Default) { classifier?.classify(state.capturedImage!!) }
                } catch (_: Exception) { null }
                _uiState.update { it.copy(classificationResult = result, classificationDone = true) }
                result
            } else {
                state.classificationResult  // reuse from previous execute
            }

            val isValidClass = classResult != null && classResult.confidence >= 0.9f
            val isImageUnidentified = hasImage && !isValidClass

            // Hard-reject: confidence so low the image is almost certainly not a coconut plant.
            // Respond immediately without spending RAG + LLM on a clearly unrelated image.
            if (hasImage && classResult != null && classResult.confidence < NON_COCONUT_THRESHOLD) {
                _uiState.update { s ->
                    s.copy(
                        phase = DiagnosisPhase.DONE,
                        streamingResponse = "",
                        conversationHistory = s.conversationHistory + ConversationTurn(
                            displayQuery = query,
                            fullUserMessage = query,
                            assistantMessage = PromptBuilder.nonCoconutMessage(lang.code)
                        )
                    )
                }
                return@launch
            }

            // ── Step 2: RAG retrieval ─────────────────────────────────────────
            _uiState.update { it.copy(phase = DiagnosisPhase.RETRIEVING) }
            val topicId = if (isValidClass) PromptBuilder.diseaseToTopicId(classResult!!.diseaseLabel) else null
            val ragQuery = if (isValidClass) "${classResult!!.displayLabel} $query" else query
            val chunks = try {
                ragRetriever?.retrieve(query = ragQuery, topicFilter = topicId, topK = 3) ?: emptyList()
            } catch (_: Exception) { emptyList() }
            _uiState.update { it.copy(retrievedChunks = chunks) }

            // ── Step 3: Build prompt ──────────────────────────────────────────
            _uiState.update { it.copy(phase = DiagnosisPhase.GENERATING) }
            if (!inferenceManager.engine.isLoaded) {
                _uiState.update { it.copy(phase = DiagnosisPhase.ERROR, errorMessage = "Model not loaded. Download a model from Model Manager.") }
                return@launch
            }

            val currentHistory = _uiState.value.conversationHistory
            val isFirstTurn = currentHistory.isEmpty()

            val fullUserMessage: String
            val prompt: String

            if (isFirstTurn) {
                fullUserMessage = PromptBuilder.buildFirstTurnUserMessage(
                    farmerQuery = query,
                    classificationResult = if (isValidClass) classResult else null,
                    retrievedChunks = chunks,
                    languageCode = lang.code,
                    hasImage = hasImage,
                    isImageUnidentified = isImageUnidentified
                )
                prompt = "<start_of_turn>user\n$fullUserMessage<end_of_turn>\n<start_of_turn>model\n"
            } else {
                fullUserMessage = query
                prompt = PromptBuilder.buildConversationPrompt(
                    history = currentHistory.takeLast(3),
                    currentQuery = query,
                    languageCode = lang.code
                )
            }

            // ── Step 4: Stream generation ─────────────────────────────────────
            val sb = StringBuilder()
            var sentenceBuf = StringBuilder()

            // When image unidentified, pass bitmap to Gemma vision for additional context
            val visionBitmap: Bitmap? = if (isImageUnidentified) state.capturedImage else null

            if (_uiState.value.isSpeakerEnabled && ttsReady) tts?.stop()

            inferenceManager.engine.generateStream(prompt, visionBitmap)
                .catch { e ->
                    _uiState.update { it.copy(phase = DiagnosisPhase.ERROR, errorMessage = e.message ?: "Generation failed") }
                }
                .onCompletion {
                    if (_uiState.value.isSpeakerEnabled && ttsReady) {
                        val rem = sentenceBuf.toString().trim()
                        if (rem.isNotBlank()) speakQueued(rem)
                    }
                    val response = sb.toString().trim()
                    _uiState.update { s ->
                        s.copy(
                            phase = DiagnosisPhase.DONE,
                            streamingResponse = "",
                            conversationHistory = s.conversationHistory + ConversationTurn(
                                displayQuery = query,
                                fullUserMessage = fullUserMessage,
                                assistantMessage = response
                            )
                        )
                    }
                }
                .collect { token ->
                    sb.append(token)
                    _uiState.update { it.copy(streamingResponse = sb.toString()) }

                    if (_uiState.value.isSpeakerEnabled && ttsReady) {
                        sentenceBuf.append(token)
                        val buf = sentenceBuf.toString()
                        val boundary = buf.indexOfFirst { it == '.' || it == '!' || it == '?' || it == '\n' }
                        if (boundary >= 0) {
                            val sentence = buf.substring(0, boundary + 1).trim()
                            if (sentence.isNotBlank()) speakQueued(sentence)
                            sentenceBuf = StringBuilder(buf.substring(boundary + 1))
                        }
                    }
                }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        val partial = _uiState.value.streamingResponse.trim()
        _uiState.update { s ->
            if (partial.isNotBlank()) {
                s.copy(
                    phase = DiagnosisPhase.IDLE,
                    streamingResponse = "",
                    conversationHistory = s.conversationHistory + ConversationTurn(
                        displayQuery = "",
                        fullUserMessage = "",
                        assistantMessage = partial
                    )
                )
            } else {
                s.copy(phase = DiagnosisPhase.IDLE, streamingResponse = "")
            }
        }
    }

    fun reset() {
        generationJob?.cancel()
        tts?.stop()
        _uiState.update {
            DiagnosisUiState(
                selectedLanguage = it.selectedLanguage,
                ragReady = it.ragReady,
                ragHasEmbedding = it.ragHasEmbedding,
                modelReady = it.modelReady,
                engineLoaded = it.engineLoaded,
                isSpeakerEnabled = it.isSpeakerEnabled
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun speakText(text: String) {
        if (!ttsReady || tts == null) return
        applyTtsLocale()
        tts?.speak(cleanForTts(text), TextToSpeech.QUEUE_FLUSH, null, "diag_${System.nanoTime()}")
    }

    private fun speakQueued(text: String) {
        if (!ttsReady || tts == null) return
        applyTtsLocale()
        tts?.speak(cleanForTts(text), TextToSpeech.QUEUE_ADD, null, "diag_${System.nanoTime()}")
    }

    private fun cleanForTts(text: String): String = text
        .replace(Regex("\\*+"), "")
        .replace(Regex("#+\\s*"), "")
        .replace(Regex("_+"), "")
        .replace(Regex("`+"), "")
        .replace(Regex("~~"), "")
        .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\.{2,}"), "")
        .replace("|", "")
        .replace(">", "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun applyTtsLocale() {
        val locale = _uiState.value.selectedLanguage.voiceLocale
        if (locale == ttsActiveLocale) return
        val result = tts!!.setLanguage(locale)
        ttsActiveLocale = if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.language = Locale.ENGLISH; Locale.ENGLISH
        } else locale
    }

    fun startRecording(retryCount: Int = 0) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _uiState.update { it.copy(errorMessage = "Speech recognition unavailable") }
            return
        }
        destroyRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { _uiState.update { it.copy(isRecording = true) } }
                override fun onPartialResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    _uiState.update { it.copy(inputQuery = t) }
                }
                override fun onResults(r: Bundle?) {
                    val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    _uiState.update { it.copy(inputQuery = t, isRecording = false) }
                    destroyRecognizer()
                }
                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            _uiState.update { it.copy(isRecording = false) }
                            destroyRecognizer()
                        }
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            destroyRecognizer()
                            if (retryCount == 0) {
                                viewModelScope.launch {
                                    kotlinx.coroutines.delay(200)
                                    startRecording(retryCount = 1)
                                }
                            } else {
                                _uiState.update { it.copy(isRecording = false) }
                            }
                        }
                        else -> {
                            _uiState.update { it.copy(isRecording = false, errorMessage = "Recognition error ($error)") }
                            destroyRecognizer()
                        }
                    }
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onBufferReceived(b: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(t: Int, p: Bundle?) = Unit
                override fun onRmsChanged(r: Float) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, _uiState.value.selectedLanguage.localeTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
    }

    fun stopRecording() {
        speechRecognizer?.stopListening()
        _uiState.update { it.copy(isRecording = false) }
    }

    private fun destroyRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    @Suppress("DEPRECATION")
    private fun uriToBitmap(uri: Uri): Bitmap {
        val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(4)
            }
        } else {
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
        return if (bmp.config == Bitmap.Config.HARDWARE) {
            bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
        } else bmp
    }

    override fun onCleared() {
        destroyRecognizer()
        tts?.stop()
        tts?.shutdown()
        classifier?.close()
        ragRetriever?.close()
        super.onCleared()
    }
}
