package xyz.geocam.snapapp

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import xyz.geocam.snapapp.databinding.ActivitySettingsBinding
import xyz.geocam.snapapp.update.UpdateChecker

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        binding.editUploadUrl.setText(prefs.getString("server_url", ""))
        binding.editUpdateUrl.setText(
            prefs.getString("update_url", UpdateChecker.DEFAULT_RELEASES_URL)
        )

        val savedQuality = prefs.getInt("jpeg_quality", DEFAULT_JPEG_QUALITY)
        binding.seekQuality.progress = savedQuality
        updateQualityLabel(savedQuality)

        binding.seekQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                updateQualityLabel(progress)
            }
            override fun onStartTrackingTouch(seek: SeekBar) {}
            override fun onStopTrackingTouch(seek: SeekBar) {}
        })

        binding.buttonRegenThumbnails.setOnClickListener {
            prefs.edit().putBoolean("regen_thumbnails", true).apply()
            finish()
        }

        binding.buttonSave.setOnClickListener {
            prefs.edit()
                .putString("server_url", normalizeUrl(binding.editUploadUrl.text.toString()))
                .putString("update_url", binding.editUpdateUrl.text.toString().trim()
                    .ifBlank { UpdateChecker.DEFAULT_RELEASES_URL })
                .putInt("jpeg_quality", binding.seekQuality.progress)
                .apply()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun normalizeUrl(raw: String): String {
        val s = raw.trim()
        if (s.isBlank()) return s
        return if (s.startsWith("http://") || s.startsWith("https://")) s
        else "https://$s"
    }

    private fun updateQualityLabel(quality: Int) {
        binding.textQualityLabel.text = getString(R.string.jpeg_quality) + ": $quality%"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val DEFAULT_JPEG_QUALITY = 85
    }
}
