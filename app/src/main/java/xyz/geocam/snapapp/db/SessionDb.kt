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
        zoomJpeg: ByteArray, midJpeg: ByteArray, wideJpeg: ByteArray
    ): Long {
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
            put("zoom_jpeg", zoomJpeg)
            put("mid_jpeg", midJpeg)
            put("wide_jpeg", wideJpeg)
        }
        return db.insertOrThrow("shots", null, cv)
    }

    fun getShotCount(): Int {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM shots", null)
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun setMeta(key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arrayOf(key, value))
    }

    fun close() = db.close()

    companion object {
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
                    zoom_jpeg BLOB NOT NULL,
                    mid_jpeg BLOB NOT NULL,
                    wide_jpeg BLOB NOT NULL
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
