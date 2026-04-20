package xyz.geocam.snapapp.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

class SessionDb(private val db: SQLiteDatabase) {

    fun insertShot(
        capturedAt: Long,
        lat: Double?, lon: Double?,
        accuracyM: Float?, altitudeM: Double?,
        locationSource: String?, locationTimeMs: Long?,
        bearingDeg: Float?, bearingAccuracyDeg: Float?,
        burstFrames: List<ByteArray>,
        midJpeg: ByteArray,
        wideJpeg: ByteArray
    ): Long {
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("captured_at", capturedAt)
                if (lat != null) put("lat", lat) else putNull("lat")
                if (lon != null) put("lon", lon) else putNull("lon")
                if (accuracyM != null) put("accuracy_m", accuracyM) else putNull("accuracy_m")
                if (altitudeM != null) put("altitude_m", altitudeM) else putNull("altitude_m")
                if (locationSource != null) put("location_source", locationSource) else putNull("location_source")
                if (locationTimeMs != null) put("location_time_ms", locationTimeMs) else putNull("location_time_ms")
                if (bearingDeg != null) put("bearing_deg", bearingDeg) else putNull("bearing_deg")
                if (bearingAccuracyDeg != null) put("bearing_accuracy_deg", bearingAccuracyDeg) else putNull("bearing_accuracy_deg")
                put("mid_jpeg", midJpeg)
                put("wide_jpeg", wideJpeg)
            }
            val shotId = db.insertOrThrow("shots", null, cv)

            burstFrames.forEachIndexed { index, bytes ->
                val bv = ContentValues().apply {
                    put("shot_id", shotId)
                    put("frame_index", index)
                    put("jpeg", bytes)
                }
                db.insertOrThrow("burst_frames", null, bv)
            }

            db.setTransactionSuccessful()
            return shotId
        } finally {
            db.endTransaction()
        }
    }

    fun getShotCount(): Int {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM shots", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun getInfo(): SessionInfo {
        val shotIds = mutableListOf<Long>()
        var firstLat: Double? = null
        var firstLon: Double? = null
        db.rawQuery("SELECT id, lat, lon FROM shots ORDER BY captured_at ASC", null).use { c ->
            while (c.moveToNext()) {
                shotIds.add(c.getLong(0))
                if (firstLat == null && !c.isNull(1)) {
                    firstLat = c.getDouble(1)
                    firstLon = if (!c.isNull(2)) c.getDouble(2) else null
                }
            }
        }
        return SessionInfo(shotIds.size, firstLat, firstLon, shotIds)
    }

    fun loadThumbnail(shotId: Long): ByteArray? {
        db.rawQuery(
            "SELECT jpeg FROM burst_frames WHERE shot_id=? AND frame_index=0",
            arrayOf(shotId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getBlob(0) else null }
    }

    fun setMeta(key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arrayOf(key, value))
    }

    fun close() = db.close()

    companion object {
        fun openReadOnly(file: File): SessionDb {
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            return SessionDb(db)
        }

        fun create(file: File): SessionDb {
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS shots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    captured_at INTEGER NOT NULL,
                    lat REAL, lon REAL,
                    accuracy_m REAL, altitude_m REAL,
                    location_source TEXT, location_time_ms INTEGER,
                    bearing_deg REAL, bearing_accuracy_deg REAL,
                    mid_jpeg BLOB NOT NULL,
                    wide_jpeg BLOB NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS burst_frames (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    shot_id INTEGER NOT NULL REFERENCES shots(id),
                    frame_index INTEGER NOT NULL,
                    jpeg BLOB NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """.trimIndent())
            return SessionDb(db)
        }
    }
}
