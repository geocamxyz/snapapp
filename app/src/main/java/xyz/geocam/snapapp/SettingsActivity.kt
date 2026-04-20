package xyz.geocam.snapapp

import android.os.Bundle
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
        binding.editUploadUrl.setText(prefs.getString("upload_url", ""))
        binding.editUpdateUrl.setText(
            prefs.getString("update_url", UpdateChecker.DEFAULT_RELEASES_URL)
        )

        binding.buttonSave.setOnClickListener {
            prefs.edit()
                .putString("upload_url", binding.editUploadUrl.text.toString().trim())
                .putString("update_url", binding.editUpdateUrl.text.toString().trim()
                    .ifBlank { UpdateChecker.DEFAULT_RELEASES_URL })
                .apply()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
