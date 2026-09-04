package com.raytek.pocketdrop

import android.app.Activity
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.Manifest
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.File
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
    private lateinit var receivedFromPc: TextView
    private lateinit var transferActivity: TextView
    private lateinit var connectionStatus: TextView
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            checkPcStatus()
            heartbeatHandler.postDelayed(this, 5_000)
        }
    }
    private var pendingSavePath: String? = null
    private var pendingSaveName: String? = null
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                getSharedPreferences("pocketdrop", MODE_PRIVATE).edit()
                    .putString("receive_folder_uri", uri.toString()).apply()
                showStatus("Default phone folder updated ✓")
            } catch (e: Exception) {
                showStatus("Could not use that folder: ${e.message}", true)
            }
        }
    }
    private val saveAsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val destination = result.data?.data
        val source = pendingSavePath?.let(::File)
        val name = pendingSaveName.orEmpty()
        if (result.resultCode == Activity.RESULT_OK && destination != null && source?.exists() == true) {
            executor.execute {
                try {
                    source.inputStream().use { input ->
                        contentResolver.openOutputStream(destination, "w").use { output ->
                            requireNotNull(output) { "Cannot open selected location" }
                            input.copyTo(output)
                        }
                    }
                    source.delete()
                    runOnUiThread { showStatus("Saved $name ✓") }
                } catch (e: Exception) {
                    runOnUiThread { showStatus("Could not save file: ${e.message}", true) }
                }
            }
        }
        pendingSavePath = null
        pendingSaveName = null
    }
    private var receiverRegistered = false
    private val arrivalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(PocketDropReceiverService.EXTRA_TYPE)) {
                "message" -> {
                    messageText.setText(intent.getStringExtra(PocketDropReceiverService.EXTRA_VALUE).orEmpty())
                    receivedFromPc.text = "Latest arrival: message from PC"
                    refreshTransferActivity()
                    showStatus("Message received from PC")
                }
                "file" -> {
                    val name = intent.getStringExtra(PocketDropReceiverService.EXTRA_VALUE).orEmpty()
                    receivedFromPc.text = "Latest file: $name\nReady to open or save"
                    refreshTransferActivity()
                    showStatus("File received from PC")
                    reviewIncomingFile(
                        name,
                        intent.getStringExtra(PocketDropReceiverService.EXTRA_PATH).orEmpty(),
                        intent.getStringExtra(PocketDropReceiverService.EXTRA_MIME) ?: "application/octet-stream"
                    )
                }
            }
        }
    }
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val value = result.contents ?: return@registerForActivityResult
        val parts = value.split('|', limit = 3)
        if (parts.size == 3 && parts[0] == "pocketdrop") {
            serverAddress.setText(parts[1])
            privateKey.setText(parts[2])
            saveConnection()
            startPhoneReceiverAndRegister()
            showStatus("PC connected ✓")
        } else {
            showStatus("That is not a LAN Send QR code", true)
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
        receivedFromPc = findViewById(R.id.receivedFromPc)
        transferActivity = findViewById(R.id.transferActivity)
        connectionStatus = findViewById(R.id.connectionStatus)
        findViewById<View>(R.id.settingsButton).setOnClickListener { showSettings() }

        val prefs = getSharedPreferences("pocketdrop", MODE_PRIVATE)
        serverAddress.setText(prefs.getString("server", ""))
        privateKey.setText(prefs.getString("token", ""))
        if (prefs.getString("server", "").orEmpty().isNotBlank()) startPhoneReceiverAndRegister()

        findViewById<Button>(R.id.scanQr).setOnClickListener {
            qrScanner.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Point at the QR code on your PC")
                setBeepEnabled(false)
                setOrientationLocked(false)
            })
        }

        findViewById<Button>(R.id.saveConnection).setOnClickListener {
            saveConnection()
            startPhoneReceiverAndRegister()
            showStatus("PC connection saved")
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
        handleReviewIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                arrivalReceiver,
                IntentFilter(PocketDropReceiverService.ACTION_RECEIVED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        showLatestArrival()
        refreshTransferActivity()
        heartbeatHandler.removeCallbacks(heartbeat)
        heartbeatHandler.post(heartbeat)
    }

    override fun onStop() {
        heartbeatHandler.removeCallbacks(heartbeat)
        if (receiverRegistered) {
            unregisterReceiver(arrivalReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun showLatestArrival() {
        val arrivals = getSharedPreferences("pocketdrop_messages", MODE_PRIVATE)
        val messageTime = arrivals.getLong("latest_time", 0L)
        val fileTime = arrivals.getLong("latest_file_time", 0L)
        if (messageTime >= fileTime && messageTime > 0L) {
            val message = arrivals.getString("latest", "").orEmpty()
            if (messageText.text.isNullOrBlank()) messageText.setText(message)
            receivedFromPc.text = "Latest arrival: message from PC"
        } else if (fileTime > 0L) {
            val name = arrivals.getString("latest_file", "").orEmpty()
            receivedFromPc.text = "Latest file: $name\nReady to open or save"
        }
    }

    private fun refreshTransferActivity() {
        transferActivity.text = TransferHistory.displayText(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleReviewIntent(intent)
    }

    private fun handleReviewIntent(intent: Intent) {
        if (intent.action != PocketDropReceiverService.ACTION_REVIEW_FILE) return
        val name = intent.getStringExtra(PocketDropReceiverService.EXTRA_VALUE).orEmpty()
        val path = intent.getStringExtra(PocketDropReceiverService.EXTRA_PATH).orEmpty()
        val mime = intent.getStringExtra(PocketDropReceiverService.EXTRA_MIME) ?: "application/octet-stream"
        intent.action = null
        reviewIncomingFile(name, path, mime)
    }

    private fun reviewIncomingFile(name: String, path: String, mime: String) {
        val file = File(path)
        if (!file.exists()) return showStatus("The temporary file is no longer available", true)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 4, 24, 8)
        }
        var dialog: AlertDialog? = null

        fun addAction(label: String, action: () -> Unit) {
            actions.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                textSize = 16f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(getColor(R.color.pocket_blue))
                minHeight = 56
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    dialog?.dismiss()
                    action()
                }
            })
        }

        addAction("Open", { openIncomingFile(file, mime) })
        addAction("Save to ${defaultFolderLabel()}", { saveToDefaultFolder(file, name, mime) })
        addAction("Choose location…", { chooseSaveLocation(file, name, mime) })

        dialog = AlertDialog.Builder(this)
            .setTitle("File received from PC")
            .setMessage(name)
            .setView(actions)
            .setNegativeButton("Not now", null)
            .create()
        dialog.show()
    }

    private fun openIncomingFile(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            showStatus("No app on this phone can open this file", true)
        }
    }

    private fun saveToDownloads(file: File, name: String, mime: String) {
        setBusy(true, "Saving $name…")
        executor.execute {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LAN Send")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Cannot create download")
                file.inputStream().use { input ->
                    contentResolver.openOutputStream(uri).use { output ->
                        requireNotNull(output) { "Cannot write download" }
                        input.copyTo(output)
                    }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                file.delete()
                runOnUiThread { setBusy(false, "Saved to Downloads/LAN Send ✓") }
            } catch (e: Exception) {
                runOnUiThread { setBusy(false, "Could not save file: ${e.message}", true) }
            }
        }
    }

    private fun saveToDefaultFolder(file: File, name: String, mime: String) {
        val savedTree = getSharedPreferences("pocketdrop", MODE_PRIVATE)
            .getString("receive_folder_uri", null)
        if (savedTree.isNullOrBlank()) {
            saveToDownloads(file, name, mime)
            return
        }
        setBusy(true, "Saving $name…")
        executor.execute {
            try {
                val treeUri = Uri.parse(savedTree)
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                val destination = DocumentsContract.createDocument(contentResolver, parentUri, mime, name)
                    ?: error("Cannot create file in selected folder")
                file.inputStream().use { input ->
                    contentResolver.openOutputStream(destination, "w").use { output ->
                        requireNotNull(output) { "Cannot write to selected folder" }
                        input.copyTo(output)
                    }
                }
                file.delete()
                runOnUiThread { setBusy(false, "Saved to ${defaultFolderLabel()} ✓") }
            } catch (e: Exception) {
                runOnUiThread { setBusy(false, "Could not save file: ${e.message}", true) }
            }
        }
    }

    private fun defaultFolderLabel(): String {
        val value = getSharedPreferences("pocketdrop", MODE_PRIVATE)
            .getString("receive_folder_uri", null) ?: return "Downloads/LAN Send"
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(value))
            id.substringAfter(':').ifBlank { id.substringBefore(':') }.ifBlank { "selected folder" }
        } catch (_: Exception) { "selected folder" }
    }

    private fun showSettings() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 8)
            addView(TextView(this@MainActivity).apply {
                text = "Files received from your PC will be saved here when you choose Save:\n\n${defaultFolderLabel()}"
                textSize = 15f
                setTextColor(getColor(R.color.pocket_text_soft))
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Phone storage")
            .setView(body)
            .setPositiveButton("Choose folder") { _, _ -> folderPicker.launch(null) }
            .setNeutralButton("Restore default") { _, _ ->
                getSharedPreferences("pocketdrop", MODE_PRIVATE).edit()
                    .remove("receive_folder_uri").apply()
                showStatus("Default restored: Downloads/LAN Send")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun chooseSaveLocation(file: File, name: String, mime: String) {
        pendingSavePath = file.absolutePath
        pendingSaveName = name
        saveAsLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        })
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

    private fun startPhoneReceiverAndRegister() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 72)
        }
        val receiverIntent = Intent(this, PocketDropReceiverService::class.java)
        startForegroundService(receiverIntent)
        executor.execute {
            try {
                Thread.sleep(250)
                val prefs = getSharedPreferences("pocketdrop", MODE_PRIVATE)
                val phoneToken = prefs.getString("phone_token", "") ?: ""
                val phoneAddress = PocketDropReceiverService.localAddress()
                if (phoneToken.isNotBlank() && phoneAddress.isNotBlank()) {
                    postBytes("/api/register", "text/plain; charset=utf-8", "$phoneAddress|$phoneToken".toByteArray(StandardCharsets.UTF_8))
                    runOnUiThread { showStatus("Two-way connection ready ✓") }
                }
            } catch (_: Exception) {
                // Phone-to-PC sending still works; registration retries next launch or save.
            }
        }
    }

    private fun normalizedServer(): String {
        var value = serverAddress.text.toString().trim().trimEnd('/')
        if (value.isNotEmpty() && !value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        return value
    }

    private fun checkPcStatus() {
        val address = normalizedServer()
        if (address.isBlank()) {
            connectionStatus.text = "● PC not configured"
            connectionStatus.setTextColor(getColor(R.color.pocket_muted))
            return
        }
        executor.execute {
            val online = try {
                val connection = URL("$address/ping").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                val result = connection.responseCode in 200..299
                connection.disconnect()
                result
            } catch (_: Exception) { false }
            runOnUiThread {
                if (!isFinishing) {
                    connectionStatus.text = if (online) "● PC online" else "● PC offline — open LAN Send on your PC"
                    connectionStatus.setTextColor(getColor(if (online) R.color.pocket_success else android.R.color.holo_orange_dark))
                }
            }
        }
    }

    private fun connectionIsReady(): Boolean {
        if (normalizedServer().isBlank() || privateKey.text.toString().trim().isBlank()) {
            showStatus("Enter the PC address and private key first", true)
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
                    TransferHistory.add(this, "Sent message: ${text.replace("\n", " ").take(45)}")
                    refreshTransferActivity()
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
        val sizes = items.associateWith { contentLength(it) }
        val totalBytes = sizes.values.filter { it > 0L }.sum()
        setBusy(true, "Sending ${items.size} item(s)…")
        executor.execute {
            try {
                var completedBytes = 0L
                items.forEachIndexed { index, uri ->
                    val name = displayName(uri)
                    val itemSize = sizes[uri] ?: -1L
                    sendUri(uri) { sentForItem ->
                        val overall = if (totalBytes > 0L && itemSize > 0L) {
                            (((completedBytes + sentForItem) * 100L) / totalBytes).toInt().coerceIn(0, 99)
                        } else {
                            (((index + (if (sentForItem > 0L) 0.5 else 0.0)) / items.size) * 100).toInt()
                        }
                        runOnUiThread {
                            progress.progress = overall
                            statusText.text = "Sending ${index + 1}/${items.size}: $name · ${readableBytes(sentForItem)}${if (itemSize > 0L) " / ${readableBytes(itemSize)}" else ""}"
                        }
                    }
                    if (itemSize > 0L) completedBytes += itemSize
                }
                runOnUiThread {
                    items.forEach { TransferHistory.add(this, "Sent file: ${displayName(it)}") }
                    refreshTransferActivity()
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

    private fun sendUri(uri: Uri, onProgress: (Long) -> Unit = {}) {
        val name = displayName(uri)
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val size = contentLength(uri)
        val connection = openConnection("/api/file")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", mime)
        connection.setRequestProperty("X-File-Name", URLEncoder.encode(name, "UTF-8"))
        if (size >= 0L) connection.setFixedLengthStreamingMode(size) else connection.setChunkedStreamingMode(64 * 1024)
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open $name" }
            connection.outputStream.use { output ->
                val source = BufferedInputStream(input)
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                var lastUpdate = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    sent += count
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 150L) {
                        onProgress(sent)
                        lastUpdate = now
                    }
                }
                onProgress(sent)
            }
        }
        verifyResponse(connection)
    }

    private fun contentLength(uri: Uri): Long {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) { -1L }
    }

    private fun readableBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do { value /= 1024.0; unit++ } while (value >= 1024.0 && unit < units.lastIndex)
        return String.format("%.1f %s", value, units[unit])
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
        return name ?: "LANSend_${System.currentTimeMillis()}"
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
            else -> "Could not reach PC: ${e.message ?: "unknown error"}"
        }
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object { private const val PICK_FILES = 41 }
}
