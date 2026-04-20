package xyz.geocam.snapapp.data

import android.content.SharedPreferences
import xyz.geocam.snapapp.db.SessionDb
import java.io.File

data class SessionFile(
    val name: String,
    val path: String,
    val lastModified: Long,
    val fileSizeBytes: Long,
    val uploadStatus: UploadStatus,
    val projectUrl: String?,
    val shotCount: Int,
    val firstLat: Double?,
    val firstLon: Double?,
    val shotIds: List<Long>
) {
    companion object {
        fun fromFile(
            file: File,
            statusPrefs: SharedPreferences,
            projectPrefs: SharedPreferences
        ): SessionFile {
            val statusStr = statusPrefs.getString(file.name, "PENDING") ?: "PENDING"
            val status = try { UploadStatus.valueOf(statusStr) } catch (e: Exception) { UploadStatus.PENDING }

            var shotCount = 0
            var firstLat: Double? = null
            var firstLon: Double? = null
            var shotIds: List<Long> = emptyList()
            try {
                val db = SessionDb.openReadOnly(file)
                val info = db.getInfo()
                db.close()
                shotCount = info.shotCount
                firstLat = info.firstLat
                firstLon = info.firstLon
                shotIds = info.shotIds
            } catch (_: Exception) {}

            return SessionFile(
                name = file.name,
                path = file.absolutePath,
                lastModified = file.lastModified(),
                fileSizeBytes = file.length(),
                uploadStatus = status,
                projectUrl = projectPrefs.getString(file.name, null),
                shotCount = shotCount,
                firstLat = firstLat,
                firstLon = firstLon,
                shotIds = shotIds
            )
        }
    }
}
