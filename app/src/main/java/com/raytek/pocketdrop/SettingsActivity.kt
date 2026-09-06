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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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
        findViewById<Button>(R.id.forgetPc).setOnClickListener { confirmForgetPc() }
        refreshFolderLabel()
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
        pairedPcStatus.text = if (preferences().getString("server", "").isNullOrBlank()) {
            "No PC is currently paired"
        } else {
            "A PC is paired with this phone"
        }
    }

    private fun confirmForgetPc() {
        if (preferences().getString("server", "").isNullOrBlank()) {
            AlertDialog.Builder(this)
                .setMessage("No PC is currently paired.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Forget paired PC?")
            .setMessage("This revokes the current connection. You will need to scan the PC QR code again. Transferred files will not be deleted.")
            .setNegativeButton("No", null)
            .setPositiveButton("Yes") { _, _ ->
                preferences().edit()
                    .remove("server")
                    .remove("token")
                    .remove("paired_pc_id")
                    .apply()
                stopService(Intent(this, PocketDropReceiverService::class.java))
                setResult(Activity.RESULT_OK)
                refreshPairedPcStatus()
            }
            .show()
    }
}
