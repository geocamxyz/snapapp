package xyz.geocam.snapapp.db

import android.database.sqlite.SQLiteDatabase
import java.io.File

class SessionDb(private val db: SQLiteDatabase) {

    fun insertShot(
        capturedAt: Long,
        lat: Double?, lon: Double?,
        accuracyM: Float?, altitudeM: Double?,
        locationSource: String?, locationTimeMs: Long?,
        bearingDeg: Float?, bearingAccuracyDeg: Float?,
        zoomJpegPath: String, midJpegPath: String, wideJpegPath: String
    ): Long {
        val sql = """
            INSERT INTO shots (
                captured_at, lat, lon, accuracy_m, altitude_m,
                location_source, location_time_ms,
                bearing_deg, bearing_accuracy_deg,
                zoom_jpeg_path, mid_jpeg_path, wide_jpeg_path
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        db.execSQL(sql, arrayOf(
            capturedAt, lat, lon, accuracyM, altitudeM,
            locationSource, locationTimeMs,
            bearingDeg, bearingAccuracyDeg,
            zoomJpegPath, midJpegPath, wideJpegPath
        ))
        return db.compileStatement("SELECT last_insert_rowid()").simpleQueryForLong()
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
                    zoom_jpeg_path TEXT NOT NULL,
                    mid_jpeg_path TEXT NOT NULL,
                    wide_jpeg_path TEXT NOT NULL
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
