package com.raytek.pocketdrop

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val selectedUris = mutableListOf<Uri>()
    private lateinit var serverAddress: EditText
    private lateinit var privateKey: EditText
    private lateinit var messageText: EditText
    private lateinit var selectedFiles: TextView
    private lateinit var sendFilesButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val value = result.contents ?: return@registerForActivityResult
        val parts = value.split('|', limit = 3)
        if (parts.size == 3 && parts[0] == "pocketdrop") {
            serverAddress.setText(parts[1])
            privateKey.setText(parts[2])
            saveConnection()
            showStatus("Desktop connected ✓")
        } else {
            showStatus("That is not a PocketDrop QR code", true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverAddress = findViewById(R.id.serverAddress)
        privateKey = findViewById(R.id.privateKey)
        messageText = findViewById(R.id.messageText)
        selectedFiles = findViewById(R.id.selectedFiles)
        sendFilesButton = findViewById(R.id.sendFiles)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)

        val prefs = getSharedPreferences("pocketdrop", MODE_PRIVATE)
        serverAddress.setText(prefs.getString("server", ""))
        privateKey.setText(prefs.getString("token", ""))

        findViewById<Button>(R.id.scanQr).setOnClickListener {
            qrScanner.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Point at the QR code on your desktop")
                setBeepEnabled(false)
                setOrientationLocked(false)
            })
        }

        findViewById<Button>(R.id.saveConnection).setOnClickListener {
            saveConnection()
            showStatus("Connection saved")
        }
        findViewById<Button>(R.id.sendMessage).setOnClickListener { sendMessage() }
        findViewById<Button>(R.id.chooseFiles).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
            }, PICK_FILES)
        }
        sendFilesButton.setOnClickListener { sendSelectedFiles() }
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILES || resultCode != Activity.RESULT_OK || data == null) return
        selectedUris.clear()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) selectedUris.add(clip.getItemAt(i).uri)
        } ?: data.data?.let(selectedUris::add)
        updateSelectedFiles()
    }

    private fun handleShareIntent(shared: Intent) {
        if (shared.action != Intent.ACTION_SEND && shared.action != Intent.ACTION_SEND_MULTIPLE) return
        selectedUris.clear()
        if (shared.action == Intent.ACTION_SEND) {
            shared.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(selectedUris::add)
            shared.getStringExtra(Intent.EXTRA_TEXT)?.let { messageText.setText(it) }
        } else {
            shared.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(selectedUris::addAll)
        }
        updateSelectedFiles()
        if (selectedUris.isNotEmpty() && connectionIsReady()) sendSelectedFiles()
    }

    private fun saveConnection() {
        getSharedPreferences("pocketdrop", MODE_PRIVATE).edit()
            .putString("server", normalizedServer())
            .putString("token", privateKey.text.toString().trim())
            .apply()
        serverAddress.setText(normalizedServer())
    }

    private fun normalizedServer(): String {
        var value = serverAddress.text.toString().trim().trimEnd('/')
        if (value.isNotEmpty() && !value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        return value
    }

    private fun connectionIsReady(): Boolean {
        if (normalizedServer().isBlank() || privateKey.text.toString().trim().isBlank()) {
            showStatus("Enter the desktop address and private key first", true)
            return false
        }
        saveConnection()
        return true
    }

    private fun sendMessage() {
        val text = messageText.text.toString()
        if (text.isBlank()) return showStatus("Type a message first", true)
        if (!connectionIsReady()) return
        setBusy(true, "Sending message…")
        executor.execute {
            try {
                postBytes("/api/text", "text/plain; charset=utf-8", text.toByteArray(StandardCharsets.UTF_8))
                runOnUiThread {
                    messageText.text.clear()
                    setBusy(false, "Message delivered ✓")
                }
            } catch (e: Exception) {
                runOnUiThread { setBusy(false, friendlyError(e), true) }
            }
        }
    }

    private fun sendSelectedFiles() {
        if (selectedUris.isEmpty()) return showStatus("Choose a file first", true)
        if (!connectionIsReady()) return
        val items = selectedUris.toList()
        setBusy(true, "Sending ${items.size} item(s)…")
        executor.execute {
            try {
                items.forEachIndexed { index, uri ->
                    runOnUiThread {
                        progress.progress = ((index.toFloat() / items.size) * 100).toInt()
                        statusText.text = "Sending ${index + 1} of ${items.size}…"
                    }
                    sendUri(uri)
                }
                runOnUiThread {
                    selectedUris.clear()
                    updateSelectedFiles()
                    progress.progress = 100
                    setBusy(false, "Delivered ${items.size} item(s) ✓")
                }
            } catch (e: Exception) {
                runOnUiThread { setBusy(false, friendlyError(e), true) }
            }
        }
    }

    private fun sendUri(uri: Uri) {
        val name = displayName(uri)
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val connection = openConnection("/api/file")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", mime)
        connection.setRequestProperty("X-File-Name", URLEncoder.encode(name, "UTF-8"))
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open $name" }
            connection.outputStream.use { output -> BufferedInputStream(input).copyTo(output) }
        }
        verifyResponse(connection)
    }

    private fun postBytes(path: String, contentType: String, bytes: ByteArray) {
        val connection = openConnection(path)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", contentType)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        verifyResponse(connection)
    }

    private fun openConnection(path: String): HttpURLConnection {
        return (URL(normalizedServer() + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("X-PocketDrop-Token", privateKey.text.toString().trim())
        }
    }

    private fun verifyResponse(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code !in 200..299) {
            val reason = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            throw IllegalStateException(reason)
        }
        connection.inputStream.close()
        connection.disconnect()
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use { if (it.moveToFirst()) name = it.getString(0) }
        return name ?: "PocketDrop_${System.currentTimeMillis()}"
    }

    private fun updateSelectedFiles() {
        selectedFiles.text = if (selectedUris.isEmpty()) "No files selected"
        else selectedUris.joinToString("\n") { "• ${displayName(it)}" }
        sendFilesButton.isEnabled = selectedUris.isNotEmpty()
    }

    private fun setBusy(busy: Boolean, message: String, error: Boolean = false) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) progress.progress = 0
        showStatus(message, error)
    }

    private fun showStatus(message: String, error: Boolean = false) {
        statusText.text = message
        statusText.setTextColor(getColor(if (error) android.R.color.holo_red_dark else R.color.pocket_blue))
    }

    private fun friendlyError(e: Exception): String {
        return when {
            e.message?.contains("401") == true || e.message?.contains("private key", true) == true -> "Private key rejected"
            else -> "Could not reach desktop: ${e.message ?: "unknown error"}"
        }
    }

    companion object { private const val PICK_FILES = 41 }
}
