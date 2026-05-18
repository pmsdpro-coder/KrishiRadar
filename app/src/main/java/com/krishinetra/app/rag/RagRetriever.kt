package com.krishiradar.app.rag

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates RAG retrieval:
 *  1. On first use: copies DB from assets, loads all 11 chunks into memory,
 *     and (if model exists) initializes the ONNX embedding service.
 *  2. At query time: embeds the query and ranks chunks by cosine similarity.
 *
 * If e5_small.onnx is absent from assets, falls back to topic-keyword matching
 * so the feature still works without the embedding model.
 */
class RagRetriever(private val context: Context) {

    companion object {
        private const val MODEL_ASSET = "e5_small.onnx"
        private const val TOKENIZER_ASSET = "e5_tokenizer.json"
        private const val MIN_SIMILARITY = 0.30f
    }

    @Volatile private var initialized = false
    private var chunks: List<ChunkWithEmbedding> = emptyList()
    private var embedder: EmbeddingService? = null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (initialized) return@withContext
        val db = KnowledgeDatabase.ensureAndOpen(context)
        chunks = KnowledgeDatabase.loadAllChunks(db)
        db.close()

        embedder = try {
            val hasModel = context.assets.list("")?.contains(MODEL_ASSET) == true
            val hasTokenizer = context.assets.list("")?.contains(TOKENIZER_ASSET) == true
            if (hasModel && hasTokenizer) EmbeddingService(context, MODEL_ASSET, TOKENIZER_ASSET)
            else null
        } catch (_: Exception) {
            null
        }
        initialized = true
    }

    suspend fun retrieve(
        query: String,
        topicFilter: String?,
        topK: Int = 5
    ): List<RetrievedChunk> = withContext(Dispatchers.Default) {
        if (!initialized) return@withContext emptyList()

        val candidates = if (topicFilter != null) {
            chunks.filter { it.topic == topicFilter }
                .ifEmpty { chunks }          // fall back to all if topic has no matches
        } else {
            chunks
        }

        val embedService = embedder
        return@withContext if (embedService != null) {
            val queryVec = try {
                embedService.embed("query: $query")
            } catch (_: Exception) {
                null
            }
            if (queryVec != null) {
                candidates
                    .map { RetrievedChunk(it, cosineSimilarity(queryVec, it.embedding)) }
                    .filter { it.similarity >= MIN_SIMILARITY }
                    .sortedByDescending { it.similarity }
                    .take(topK)
            } else {
                candidates.take(topK).map { RetrievedChunk(it, 1f) }
            }
        } else {
            // Fallback: return all candidates for the topic (no ranking by similarity)
            candidates.take(topK).map { RetrievedChunk(it, 1f) }
        }
    }

    fun isEmbeddingAvailable(): Boolean = embedder != null

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val len = minOf(a.size, b.size)
        var dot = 0f
        for (i in 0 until len) dot += a[i] * b[i]
        return dot  // both are L2-normalized
    }

    fun close() {
        embedder?.close()
        embedder = null
        initialized = false
    }
}
