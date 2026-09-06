package com.raytek.pocketdrop

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var folderLabel: TextView
    private lateinit var pairedPcStatus: TextView

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                preferences().edit().putString("receive_folder_uri", uri.toString()).apply()
                refreshFolderLabel()
            } catch (e: Exception) {
                AlertDialog.Builder(this)
                    .setTitle("Folder not changed")
                    .setMessage("LAN Send could not use that folder. Please choose another folder.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private val pairedPcLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        setResult(Activity.RESULT_OK)
        refreshPairedPcStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setResult(Activity.RESULT_OK)

        folderLabel = findViewById(R.id.defaultFolderValue)
        pairedPcStatus = findViewById(R.id.pairedPcStatus)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        val receiveModes = findViewById<RadioGroup>(R.id.receiveModes)
        when (preferences().getString("phone_receive_mode", "ask")) {
            "auto" -> receiveModes.check(R.id.receiveAutomatically)
            "blocked" -> receiveModes.check(R.id.receiveDisabled)
            else -> receiveModes.check(R.id.receiveAsk)
        }
        receiveModes.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.receiveAutomatically -> "auto"
                R.id.receiveDisabled -> "blocked"
                else -> "ask"
            }
            preferences().edit().putString("phone_receive_mode", mode).apply()
        }

        findViewById<Button>(R.id.changeFolder).setOnClickListener { folderPicker.launch(null) }
        findViewById<Button>(R.id.restoreFolder).setOnClickListener {
            preferences().edit().remove("receive_folder_uri").apply()
            refreshFolderLabel()
        }
        findViewById<Button>(R.id.forgetPc).apply {
            text = "Manage PCs"
            setTextColor(getColor(R.color.pocket_blue))
            setOnClickListener {
                pairedPcLauncher.launch(Intent(this@SettingsActivity, PairedPcActivity::class.java))
            }
        }
        refreshFolderLabel()
        refreshPairedPcStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshPairedPcStatus()
    }

    private fun preferences() = getSharedPreferences("pocketdrop", MODE_PRIVATE)

    private fun refreshFolderLabel() {
        folderLabel.text = defaultFolderLabel()
    }

    private fun defaultFolderLabel(): String {
        val value = preferences().getString("receive_folder_uri", null) ?: return "Downloads/LAN Send"
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(value))
            id.substringAfter(':').ifBlank { id.substringBefore(':') }.ifBlank { "Selected folder" }
        } catch (_: Exception) {
            "Selected folder"
        }
    }

    private fun refreshPairedPcStatus() {
        val records = PairedPcStore.records(this)
        val active = PairedPcStore.activeRecord(this)
        pairedPcStatus.text = when {
            records.isEmpty() -> "No PC is currently remembered"
            active != null -> "${records.size} of ${PairedPcStore.MAX_REMEMBERED_PCS} PCs remembered\nSelected: ${active.name}"
            else -> "${records.size} of ${PairedPcStore.MAX_REMEMBERED_PCS} PCs remembered"
        }
    }
}
