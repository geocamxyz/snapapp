package xyz.geocam.snapapp.recognition

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shard format on disk (directory):
 *   vectors.bin  – [int32 n][int32 dim][n*dim float32 vectors row-major][n int64 ids]
 *   meta.db      – SQLite: id INTEGER PRIMARY KEY, lat REAL, lon REAL, label TEXT
 *   extent.json  – {"id":"...","polygon":[[lat,lon],...]}
 *
 * Vectors must be L2-normalised float32. Search uses inner product (== cosine sim).
 */
class SearchIndex(shardDir: File) : AutoCloseable {

    private val handle: Long
    private val metaDb: SQLiteDatabase

    init {
        val bin = File(shardDir, "vectors.bin")
        val bytes = bin.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val n   = buf.int
        val dim = buf.int

        val vectorsArr = FloatArray(n * dim)
        buf.asFloatBuffer().get(vectorsArr)

        // IDs follow immediately after the float block
        val idOffset = 8 + n.toLong() * dim * 4
        val idBuf = ByteBuffer.wrap(bytes, idOffset.toInt(), n * 8).order(ByteOrder.LITTLE_ENDIAN)
        val idsArr = LongArray(n) { idBuf.long }

        handle = nativeLoad(vectorsArr, idsArr, dim)
        metaDb = SQLiteDatabase.openDatabase(
            File(shardDir, "meta.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
    }

    /** Returns the best match above [threshold], or null. */
    fun search(query: FloatArray, topK: Int = 5, threshold: Float = MATCH_THRESHOLD): MatchResult? {
        val raw = nativeSearch(handle, query, topK) ?: return null
        val id    = raw[0].toLong()
        val score = raw[1]
        if (score < threshold) return null

        metaDb.rawQuery("SELECT lat, lon, label FROM records WHERE id=?",
            arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return MatchResult(id, score, c.getDouble(0), c.getDouble(1), c.getString(2))
        }
    }

    override fun close() {
        nativeRelease(handle)
        metaDb.close()
    }

    companion object {
        const val MATCH_THRESHOLD = 0.75f

        init { System.loadLibrary("snapapp-search") }

        @JvmStatic external fun nativeLoad(vectors: FloatArray, ids: LongArray, dim: Int): Long
        @JvmStatic external fun nativeSearch(handle: Long, query: FloatArray, topK: Int): FloatArray?
        @JvmStatic external fun nativeRelease(handle: Long)
    }
}
