package com.obsidianwidget

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class QuickCaptureActivity : AppCompatActivity() {

    private lateinit var vaultManager: VaultManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_capture)

        vaultManager = VaultManager(this)

        val captureInput = findViewById<EditText>(R.id.capture_input)

        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val text = captureInput.text.toString().trim()
            if (text.isBlank()) {
                finish()
                return@setOnClickListener
            }

            if (!vaultManager.isVaultConfigured) {
                Toast.makeText(this, R.string.vault_not_configured, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val success = vaultManager.appendToDailyNote(text)
            if (success) {
                Toast.makeText(this, R.string.note_saved, Toast.LENGTH_SHORT).show()
                ObsidianWidgetProvider.updateAllWidgets(this)
            } else {
                Toast.makeText(this, R.string.error_saving, Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        // Handle shared text from other apps
        if (intent?.action == android.content.Intent.ACTION_SEND) {
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let {
                captureInput.setText(it)
            }
        }
    }
}
