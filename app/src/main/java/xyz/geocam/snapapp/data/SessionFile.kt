package xyz.geocam.snapapp.data

import android.content.SharedPreferences
import java.io.File

data class SessionFile(
    val name: String,
    val path: String,
    val lastModified: Long,
    val uploadStatus: UploadStatus
) {
    companion object {
        fun fromFile(file: File, prefs: SharedPreferences): SessionFile {
            val statusStr = prefs.getString(file.name, "PENDING") ?: "PENDING"
            val status = try { UploadStatus.valueOf(statusStr) } catch (e: Exception) { UploadStatus.PENDING }
            return SessionFile(
                name = file.name,
                path = file.absolutePath,
                lastModified = file.lastModified(),
                uploadStatus = status
            )
        }
    }
}
