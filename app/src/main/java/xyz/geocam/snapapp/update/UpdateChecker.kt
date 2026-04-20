package xyz.geocam.snapapp.update

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

class UpdateChecker(private val context: Context) {

    private val http = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main)

    fun check(currentVersionCode: Int) {
        val updateUrl = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("update_url", DEFAULT_RELEASES_URL) ?: DEFAULT_RELEASES_URL

        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { fetchLatestRelease(updateUrl) }
                if (info == null) {
                    Toast.makeText(context, "Could not check for updates", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (info.versionCode > currentVersionCode) {
                    showUpdateDialog(info)
                } else {
                    Toast.makeText(context, "App is up to date (v${currentVersionCode})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Update check failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchLatestRelease(url: String): ReleaseInfo? {
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) return null
        val json = JSONObject(response.body!!.string())
        val tag = json.getString("tag_name") // e.g. "v1.0.42"
        val versionCode = tag.removePrefix("v").substringAfterLast(".").toIntOrNull() ?: return null
        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) {
                apkUrl = asset.getString("browser_download_url")
                break
            }
        }
        return if (apkUrl != null) ReleaseInfo(tag, versionCode, apkUrl) else null
    }

    private fun showUpdateDialog(info: ReleaseInfo) {
        AlertDialog.Builder(context)
            .setTitle("Update available")
            .setMessage("Version ${info.tag} is available. Download and install?")
            .setPositiveButton("Install") { _, _ -> downloadAndInstall(info.apkUrl) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(apkUrl: String) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "Enable 'Install unknown apps' for SnapApp in Settings", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            })
            return
        }
        Toast.makeText(context, "Downloading update…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val dest = File(context.cacheDir, "update.apk")
                val response = http.newCall(Request.Builder().url(apkUrl).build()).execute()
                dest.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
                dest
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private data class ReleaseInfo(val tag: String, val versionCode: Int, val apkUrl: String)

    companion object {
        const val DEFAULT_RELEASES_URL = "https://api.github.com/repos/geocamxyz/snapapp/releases/latest"
    }
}
