package xyz.geocam.snapapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.geocam.snapapp.data.SessionFile
import xyz.geocam.snapapp.data.UploadStatus
import xyz.geocam.snapapp.databinding.ActivityMainBinding
import xyz.geocam.snapapp.update.UpdateChecker
import xyz.geocam.snapapp.upload.SessionUploader
import xyz.geocam.snapapp.upload.UploadResult
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SessionAdapter
    private val activeUploads = mutableMapOf<String, Job>()
    private var pendingInstallUrl: String? = null

    private val installPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val url = pendingInstallUrl ?: return@registerForActivityResult
        if (packageManager.canRequestPackageInstalls()) {
            pendingInstallUrl = null
            makeUpdateChecker().triggerInstall(url)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = SessionAdapter(
            scope = lifecycleScope,
            onUpload = ::uploadSession,
            onShare = ::shareSession,
            onView = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            onDelete = ::deleteSession,
            isUploading = { name -> activeUploads.containsKey(name) }
        )
        binding.recyclerView.adapter = adapter

        binding.fabNewSession.setOnClickListener {
            startActivity(Intent(this, SessionActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadSessions()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.action_check_updates -> {
            makeUpdateChecker().check(BuildConfig.VERSION_CODE)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun makeUpdateChecker(): UpdateChecker = UpdateChecker(this) { apkUrl ->
        pendingInstallUrl = apkUrl
        installPermLauncher.launch(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    private fun loadSessions() {
        lifecycleScope.launch {
            val statusPrefs = getSharedPreferences("upload_status", MODE_PRIVATE)
            val projectPrefs = getSharedPreferences("project_urls", MODE_PRIVATE)

            // Any session marked UPLOADING but with no active job is a stale state
            // from a previous process — reset it so the user can retry
            withContext(Dispatchers.IO) {
                val edit = statusPrefs.edit()
                statusPrefs.all.forEach { (key, value) ->
                    if (value == "UPLOADING" && !activeUploads.containsKey(key)) {
                        edit.putString(key, "PENDING")
                    }
                }
                edit.apply()
            }

            val sessions = withContext(Dispatchers.IO) {
                filesDir.listFiles { f -> f.extension == "db" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.map { SessionFile.fromFile(it, statusPrefs, projectPrefs) }
                    ?: emptyList()
            }
            adapter.submitList(sessions)
        }
    }

    private fun shareSession(session: SessionFile) {
        val uri: Uri = FileProvider.getUriForFile(
            this, "${packageName}.fileprovider", File(session.path)
        )
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            session.name
        ))
    }

    private fun deleteSession(session: SessionFile) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete session")
            .setMessage("Delete \"${session.name.removeSuffix(".db")}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                activeUploads[session.name]?.cancel()
                activeUploads.remove(session.name)
                lifecycleScope.launch(Dispatchers.IO) {
                    File(session.path).delete()
                    getSharedPreferences("upload_status", MODE_PRIVATE).edit().remove(session.name).apply()
                    getSharedPreferences("project_urls", MODE_PRIVATE).edit().remove(session.name).apply()
                    getSharedPreferences("upload_ids", MODE_PRIVATE).edit().remove(session.name).apply()
                    withContext(Dispatchers.Main) { loadSessions() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun uploadSession(session: SessionFile) {
        // If already uploading in this process, ignore tap
        if (activeUploads.containsKey(session.name)) return

        val baseUrl = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("server_url", "") ?: ""
        if (baseUrl.isBlank()) {
            Toast.makeText(this, "Configure server URL in Settings first", Toast.LENGTH_SHORT).show()
            return
        }

        val statusPrefs = getSharedPreferences("upload_status", MODE_PRIVATE)
        statusPrefs.edit().putString(session.name, "UPLOADING").apply()
        adapter.updateStatus(session.name, UploadStatus.UPLOADING)

        val job = lifecycleScope.launch {
            val result = SessionUploader(this@MainActivity).upload(
                dbFile = File(session.path),
                baseUrl = baseUrl,
                onProgress = { sent, total ->
                    val pct = if (total > 0) (sent * 100 / total).toInt() else 0
                    runOnUiThread { adapter.updateProgress(session.name, pct) }
                }
            )
            activeUploads.remove(session.name)
            when (result) {
                is UploadResult.Success -> {
                    statusPrefs.edit().putString(session.name, "UPLOADED").apply()
                    getSharedPreferences("project_urls", MODE_PRIVATE)
                        .edit().putString(session.name, result.projectUrl).apply()
                    adapter.updateStatus(session.name, UploadStatus.UPLOADED)
                    adapter.updateProjectUrl(session.name, result.projectUrl)
                }
                is UploadResult.Failure -> {
                    statusPrefs.edit().putString(session.name, "ERROR").apply()
                    adapter.updateStatus(session.name, UploadStatus.ERROR)
                    Toast.makeText(
                        this@MainActivity,
                        "Upload failed: ${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        activeUploads[session.name] = job
    }
}
