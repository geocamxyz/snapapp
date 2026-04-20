package xyz.geocam.snapapp.data

import android.content.SharedPreferences
import java.io.File

data class SessionFile(
    val name: String,
    val path: String,
    val lastModified: Long,
    val uploadStatus: UploadStatus,
    val projectUrl: String?
) {
    companion object {
        fun fromFile(
            file: File,
            statusPrefs: SharedPreferences,
            projectPrefs: SharedPreferences
        ): SessionFile {
            val statusStr = statusPrefs.getString(file.name, "PENDING") ?: "PENDING"
            val status = try { UploadStatus.valueOf(statusStr) } catch (e: Exception) { UploadStatus.PENDING }
            return SessionFile(
                name = file.name,
                path = file.absolutePath,
                lastModified = file.lastModified(),
                uploadStatus = status,
                projectUrl = projectPrefs.getString(file.name, null)
            )
        }
    }
}
