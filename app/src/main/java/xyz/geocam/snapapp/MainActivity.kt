package xyz.geocam.snapapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.geocam.snapapp.data.SessionFile
import xyz.geocam.snapapp.data.UploadStatus
import xyz.geocam.snapapp.databinding.ActivityMainBinding
import androidx.core.content.FileProvider
import xyz.geocam.snapapp.update.UpdateChecker
import xyz.geocam.snapapp.upload.SessionUploader
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SessionAdapter
    private val uploader = SessionUploader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = SessionAdapter(onUpload = ::uploadSession, onShare = ::shareSession)
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
            UpdateChecker(this).check(BuildConfig.VERSION_CODE)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadSessions() {
        lifecycleScope.launch {
            val prefs = getSharedPreferences("upload_status", MODE_PRIVATE)
            val sessions = withContext(Dispatchers.IO) {
                filesDir.listFiles { f -> f.extension == "db" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.map { SessionFile.fromFile(it, prefs) }
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

    private fun uploadSession(session: SessionFile) {
        val uploadUrl = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("upload_url", "") ?: ""
        if (uploadUrl.isBlank()) {
            Toast.makeText(this, "Configure upload URL in Settings first", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = getSharedPreferences("upload_status", MODE_PRIVATE)
        prefs.edit().putString(session.name, "UPLOADING").apply()
        adapter.updateStatus(session.name, UploadStatus.UPLOADING)

        uploader.upload(File(session.path), uploadUrl) { success ->
            runOnUiThread {
                val status = if (success) "UPLOADED" else "ERROR"
                prefs.edit().putString(session.name, status).apply()
                adapter.updateStatus(session.name, if (success) UploadStatus.UPLOADED else UploadStatus.ERROR)
            }
        }
    }
}
