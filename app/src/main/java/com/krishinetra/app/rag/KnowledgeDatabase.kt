package com.krishiradar.app.rag

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ChunkWithEmbedding(
    val chunkId: String,
    val topic: String,
    val section: String,
    val text: String,
    val sourceName: String,
    val sourceUrl: String,
    val embedding: FloatArray
)

data class RetrievedChunk(
    val chunk: ChunkWithEmbedding,
    val similarity: Float
)

object KnowledgeDatabase {

    private const val DB_ASSET = "coconut_kb.db"

    fun ensureAndOpen(context: Context): SQLiteDatabase {
        val dbFile = File(context.filesDir, DB_ASSET)
        val assetSize = context.assets.openFd(DB_ASSET).use { it.length }
        if (!dbFile.exists() || dbFile.length() != assetSize) {
            val tmp = File(dbFile.parent, dbFile.name + ".tmp")
            context.assets.open(DB_ASSET).use { input ->
                FileOutputStream(tmp).use { input.copyTo(it) }
            }
            if (!tmp.renameTo(dbFile)) {
                tmp.copyTo(dbFile, overwrite = true)
                tmp.delete()
            }
        }
        return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    fun loadAllChunks(db: SQLiteDatabase): List<ChunkWithEmbedding> {
        val chunks = mutableListOf<ChunkWithEmbedding>()
        db.rawQuery(
            "SELECT chunk_id, source_name, source_url, topic, section, text, embedding FROM chunks",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val embBytes = cursor.getBlob(6)
                chunks.add(
                    ChunkWithEmbedding(
                        chunkId = cursor.getString(0),
                        sourceName = cursor.getString(1),
                        sourceUrl = cursor.getString(2),
                        topic = cursor.getString(3),
                        section = cursor.getString(4),
                        text = cursor.getString(5),
                        embedding = deserializeEmbedding(embBytes)
                    )
                )
            }
        }
        return chunks
    }

    private fun deserializeEmbedding(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }
}
