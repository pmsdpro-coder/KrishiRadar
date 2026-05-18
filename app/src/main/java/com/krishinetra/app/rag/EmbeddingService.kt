package com.krishiradar.app.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Runs multilingual-e5-small (ONNX INT8) to embed text queries at runtime.
 * Expects assets: e5_small.onnx and e5_tokenizer.json
 *
 * Tokenizer: XLM-RoBERTa Unigram (SentencePiece Metaspace), as exported by
 * optimum.onnxruntime.ORTModelForFeatureExtraction.
 */
class EmbeddingService(context: Context, modelAsset: String, tokenizerAsset: String) {

    companion object {
        private const val MAX_SEQ_LEN = 512
        private const val MAX_PIECE_LEN = 32
        private const val BOS_ID = 3   // <s>
        private const val EOS_ID = 2   // </s>
        private const val UNK_ID = 0   // <unk>
        private val METASPACE = "▁"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val vocab: Map<String, Pair<Int, Float>>
    private val hasTokenTypeIds: Boolean

    init {
        val tokenizerJson = context.assets.open(tokenizerAsset).bufferedReader().use { it.readText() }
        vocab = parseVocab(tokenizerJson)

        val modelFile = File(context.filesDir, modelAsset.substringAfterLast('/'))
        val assetSize = context.assets.openFd(modelAsset).use { it.length }
        if (!modelFile.exists() || modelFile.length() != assetSize) {
            val tmp = File(modelFile.parent, modelFile.name + ".tmp")
            context.assets.open(modelAsset).use { input ->
                FileOutputStream(tmp).use { input.copyTo(it, bufferSize = 1024 * 1024) }
            }
            if (!tmp.renameTo(modelFile)) {
                // renameTo can fail cross-filesystem (e.g. external → internal); fall back to copy
                tmp.copyTo(modelFile, overwrite = true)
                tmp.delete()
            }
        }

        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        session = env.createSession(modelFile.absolutePath, opts)
        hasTokenTypeIds = "token_type_ids" in session.inputNames
    }

    fun embed(text: String): FloatArray {
        val (inputIds, attentionMask) = tokenize(text)
        val seqLen = inputIds.size.toLong()

        val inputs = mutableMapOf<String, OnnxTensorLike>(
            "input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, seqLen)),
            "attention_mask" to OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), longArrayOf(1, seqLen))
        )
        if (hasTokenTypeIds) {
            inputs["token_type_ids"] = OnnxTensor.createTensor(
                env, LongBuffer.wrap(LongArray(inputIds.size) { 0L }), longArrayOf(1, seqLen)
            )
        }

        try {
            return session.run(inputs).use { result ->
                val tensor = result.get(0) as OnnxTensor
                val shape = tensor.info.shape          // [1, seqLen, hiddenDim]
                val hiddenDim = shape[2].toInt()
                val flatData = tensor.floatBuffer

                val pooled = FloatArray(hiddenDim)
                var count = 0
                for (t in 0 until seqLen.toInt()) {
                    if (attentionMask[t] == 1L) {
                        for (d in 0 until hiddenDim) pooled[d] += flatData[t * hiddenDim + d]
                        count++
                    }
                }
                if (count > 0) for (d in 0 until hiddenDim) pooled[d] /= count

                l2Normalize(pooled)
            }
        } finally {
            inputs.values.forEach { (it as? OnnxTensor)?.close() }
        }
    }

    private fun tokenize(text: String): Pair<LongArray, LongArray> {
        val ids = mutableListOf(BOS_ID)
        val normalized = METASPACE + text.trim().replace(" ", METASPACE)
        var wordBuf = StringBuilder()
        for (ch in normalized) {
            if (ch == METASPACE[0] && wordBuf.isNotEmpty()) {
                ids.addAll(segmentWord(wordBuf.toString()))
                wordBuf = StringBuilder()
            }
            wordBuf.append(ch)
        }
        if (wordBuf.isNotEmpty()) ids.addAll(segmentWord(wordBuf.toString()))
        ids.add(EOS_ID)

        val clipped = ids.take(MAX_SEQ_LEN)
        return LongArray(clipped.size) { clipped[it].toLong() } to LongArray(clipped.size) { 1L }
    }

    private fun segmentWord(word: String): List<Int> {
        if (word.isEmpty()) return emptyList()
        vocab[word]?.let { return listOf(it.first) }

        val n = word.length
        val dp = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val bp = IntArray(n + 1)
        dp[0] = 0f

        for (end in 1..n) {
            for (start in maxOf(0, end - MAX_PIECE_LEN) until end) {
                if (dp[start] == Float.NEGATIVE_INFINITY) continue
                val piece = word.substring(start, end)
                val entry = vocab[piece] ?: continue
                val score = dp[start] + entry.second
                if (score > dp[end]) { dp[end] = score; bp[end] = end - start }
            }
            // Byte fallback for characters not in vocab
            if (dp[end] == Float.NEGATIVE_INFINITY && dp[end - 1] != Float.NEGATIVE_INFINITY) {
                dp[end] = dp[end - 1] - 20f
                bp[end] = 1
            }
        }

        val pieces = mutableListOf<Int>()
        var pos = n
        while (pos > 0) {
            val len = bp[pos].coerceAtLeast(1)
            pieces.add(vocab[word.substring(pos - len, pos)]?.first ?: UNK_ID)
            pos -= len
        }
        return pieces.reversed()
    }

    private fun parseVocab(json: String): Map<String, Pair<Int, Float>> {
        val map = HashMap<String, Pair<Int, Float>>()
        val vocabArr = JSONObject(json).getJSONObject("model").getJSONArray("vocab")
        for (i in 0 until vocabArr.length()) {
            val entry = vocabArr.getJSONArray(i)
            map[entry.getString(0)] = i to entry.getDouble(1).toFloat()
        }
        return map
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, f -> acc + f * f }).coerceAtLeast(1e-12f)
        return FloatArray(v.size) { v[it] / norm }
    }

    fun close() {
        session.close()
        // Do NOT close env — OrtEnvironment.getEnvironment() is a process-wide singleton;
        // closing it would crash any subsequent ONNX call in the same process.
    }
}
