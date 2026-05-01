package xyz.geocam.snapapp.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

class SessionDb(private val db: SQLiteDatabase) : AutoCloseable {

    fun insertShot(
        capturedAt: Long,
        lat: Double?, lon: Double?,
        accuracyM: Float?, altitudeM: Double?,
        locationSource: String?, locationTimeMs: Long?,
        bearingDeg: Float?, bearingAccuracyDeg: Float?,
        zoomRatio: Float,
        widePrejpeg: ByteArray,
        burstFrames: List<ByteArray>,
        midJpeg: ByteArray,
        wideJpeg: ByteArray,
        isWideScan: Boolean = false
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
                put("zoom_ratio", zoomRatio)
                put("wide_pre_jpeg", widePrejpeg)
                put("mid_jpeg", midJpeg)
                put("wide_jpeg", wideJpeg)
                put("is_wide_scan", if (isWideScan) 1 else 0)
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

    // Calibration shots orbit an object alternating wide/mid zoom.
    // is_wide_scan=1 marks them as non-triangulation so MIN_SHOTS guidance is unaffected.
    fun insertCalibrationShot(capturedAt: Long, lat: Double?, lon: Double?, jpeg: ByteArray, zoomRatio: Float) {
        insertShot(
            capturedAt = capturedAt,
            lat = lat, lon = lon,
            accuracyM = null, altitudeM = null,
            locationSource = null, locationTimeMs = null,
            bearingDeg = null, bearingAccuracyDeg = null,
            zoomRatio = zoomRatio,
            widePrejpeg = jpeg,
            burstFrames = listOf(jpeg),
            midJpeg = jpeg,
            wideJpeg = jpeg,
            isWideScan = true
        )
    }

    // Counts only burst shots (is_wide_scan=0); used for triangulation guidance.
    fun getShotCount(): Int {
        return try {
            db.rawQuery("SELECT COUNT(*) FROM shots WHERE is_wide_scan = 0", null)
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) {
            // Old DB without is_wide_scan column — all shots are burst shots
            db.rawQuery("SELECT COUNT(*) FROM shots", null)
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }
    }

    fun getInfo(): SessionInfo {
        val shotIds = mutableListOf<Long>()
        var burstCount = 0
        var firstLat: Double? = null
        var firstLon: Double? = null
        try {
            db.rawQuery("SELECT id, lat, lon, is_wide_scan FROM shots ORDER BY captured_at ASC", null).use { c ->
                while (c.moveToNext()) {
                    shotIds.add(c.getLong(0))
                    if (c.getInt(3) == 0) burstCount++
                    if (firstLat == null && !c.isNull(1)) {
                        firstLat = c.getDouble(1)
                        firstLon = if (!c.isNull(2)) c.getDouble(2) else null
                    }
                }
            }
        } catch (e: Exception) {
            // Old DB without is_wide_scan column
            db.rawQuery("SELECT id, lat, lon FROM shots ORDER BY captured_at ASC", null).use { c ->
                while (c.moveToNext()) {
                    shotIds.add(c.getLong(0))
                    burstCount++
                    if (firstLat == null && !c.isNull(1)) {
                        firstLat = c.getDouble(1)
                        firstLon = if (!c.isNull(2)) c.getDouble(2) else null
                    }
                }
            }
        }
        // Backward compat: old sessions may have data in wide_scan_frames table
        val wideScanIds = mutableListOf<Long>()
        try {
            db.rawQuery("SELECT id, lat, lon FROM wide_scan_frames ORDER BY captured_at ASC", null).use { c ->
                while (c.moveToNext()) {
                    wideScanIds.add(c.getLong(0))
                    if (firstLat == null && !c.isNull(1)) {
                        firstLat = c.getDouble(1)
                        firstLon = if (!c.isNull(2)) c.getDouble(2) else null
                    }
                }
            }
        } catch (e: Exception) { /* table may not exist in very old DBs */ }
        return SessionInfo(burstCount, firstLat, firstLon, shotIds, wideScanIds)
    }

    fun loadThumbnail(shotId: Long): ByteArray? {
        db.rawQuery(
            "SELECT jpeg FROM burst_frames WHERE shot_id=? AND frame_index=0",
            arrayOf(shotId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getBlob(0) else null }
    }

    fun loadWideScanThumbnail(frameId: Long): ByteArray? {
        db.rawQuery(
            "SELECT jpeg FROM wide_scan_frames WHERE id=?",
            arrayOf(frameId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getBlob(0) else null }
    }

    fun deleteShot(shotId: Long) {
        db.beginTransaction()
        try {
            db.delete("burst_frames", "shot_id=?", arrayOf(shotId.toString()))
            db.delete("shots", "id=?", arrayOf(shotId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setMeta(key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arrayOf(key, value))
    }

    override fun close() = db.close()

    companion object {
        fun openReadWrite(file: File): SessionDb {
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            return SessionDb(db)
        }

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
                    zoom_ratio REAL NOT NULL,
                    wide_pre_jpeg BLOB NOT NULL,
                    mid_jpeg BLOB NOT NULL,
                    wide_jpeg BLOB NOT NULL,
                    is_wide_scan INTEGER NOT NULL DEFAULT 0
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
                CREATE TABLE IF NOT EXISTS wide_scan_frames (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    captured_at INTEGER NOT NULL,
                    lat REAL,
                    lon REAL,
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
