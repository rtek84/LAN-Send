package com.raytek.pocketdrop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.net.Uri
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

class PocketDropReceiverService : Service() {
    private val listenerWorker = Executors.newSingleThreadExecutor()
    private val transferWorkers = Executors.newFixedThreadPool(4)
    @Volatile private var running = true
    private var server: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1001, NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("PocketDrop is ready")
            .setContentText("Your PC can send files and messages to this phone")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build())

        val prefs = getSharedPreferences("pocketdrop", MODE_PRIVATE)
        if (prefs.getString("phone_token", null).isNullOrBlank()) {
            prefs.edit().putString("phone_token", UUID.randomUUID().toString().replace("-", "")).apply()
        }
        listenerWorker.execute { listen() }
    }

    private fun listen() {
        try {
            server = ServerSocket(PORT)
            while (running) {
                val client = server!!.accept()
                transferWorkers.execute { handle(client) }
            }
        } catch (_: Exception) { }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 30_000
                val input = BufferedInputStream(client.getInputStream())
                val request = readLine(input).split(' ')
                if (request.size < 2) return@use
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty()) break
                    val split = line.indexOf(':')
                    if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
                }
                val expected = getSharedPreferences("pocketdrop", MODE_PRIVATE).getString("phone_token", "")
                if (headers["x-pocketdrop-token"] != expected) return respond(client, 401, "Private key rejected")
                val length = headers["content-length"]?.toLongOrNull() ?: 0L
                when (request[1]) {
                    "/ping" -> respond(client, 200, "PocketDrop phone is ready")
                    "/api/text" -> {
                        val text = readBody(input, length).toString(StandardCharsets.UTF_8)
                        getSharedPreferences("pocketdrop_messages", MODE_PRIVATE).edit()
                            .putString("latest", text).putLong("latest_time", System.currentTimeMillis()).apply()
                        TransferHistory.add(this, "Received message: ${text.replace("\n", " ").take(45)}")
                        sendBroadcast(Intent(ACTION_RECEIVED).setPackage(packageName)
                            .putExtra(EXTRA_TYPE, "message").putExtra(EXTRA_VALUE, text))
                        notifyArrival("Message from PC", text.take(120))
                        respond(client, 200, "OK")
                    }
                    "/api/file" -> {
                        val encoded = headers["x-file-name"] ?: "PocketDrop_file"
                        val name = URLDecoder.decode(encoded, "UTF-8").substringAfterLast('/').substringAfterLast('\\')
                        val mime = headers["content-type"] ?: "application/octet-stream"
                        val file = saveIncomingFile(name, input, length)
                        getSharedPreferences("pocketdrop_messages", MODE_PRIVATE).edit()
                            .putString("latest_file", name)
                            .putString("latest_file_path", file.absolutePath)
                            .putString("latest_file_mime", mime)
                            .putLong("latest_file_time", System.currentTimeMillis()).apply()
                        TransferHistory.add(this, "Received file: $name")
                        sendBroadcast(Intent(ACTION_RECEIVED).setPackage(packageName)
                            .putExtra(EXTRA_TYPE, "file")
                            .putExtra(EXTRA_VALUE, name)
                            .putExtra(EXTRA_PATH, file.absolutePath)
                            .putExtra(EXTRA_MIME, mime))
                        notifyFileArrival(name, file.absolutePath, mime)
                        respond(client, 200, "OK")
                    }
                    else -> respond(client, 404, "Not found")
                }
            } catch (_: Exception) { }
        }
    }

    private fun saveIncomingFile(name: String, input: BufferedInputStream, length: Long): File {
        val directory = File(getExternalFilesDir(null) ?: filesDir, "incoming").apply { mkdirs() }
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "PocketDrop_file" }
        var file = File(directory, safeName)
        if (file.exists()) {
            val base = safeName.substringBeforeLast('.', safeName)
            val extension = safeName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
            file = File(directory, "${base}_${System.currentTimeMillis()}$extension")
        }
        file.outputStream().buffered().use { output ->
            var remaining = length
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) break
                output.write(buffer, 0, count); remaining -= count
            }
        }
        return file
    }

    private fun readBody(input: BufferedInputStream, length: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        var remaining = length
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            output.write(buffer, 0, count); remaining -= count
        }
        return output.toByteArray()
    }

    private fun readLine(input: BufferedInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val current = input.read()
            if (current < 0) break
            if (previous == 13 && current == 10) break
            if (previous >= 0) bytes.write(previous)
            previous = current
        }
        return bytes.toString("UTF-8")
    }

    private fun respond(socket: Socket, code: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        BufferedOutputStream(socket.getOutputStream()).use {
            it.write("HTTP/1.1 $code OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            it.write(bytes); it.flush()
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "PocketDrop receiver", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ARRIVALS, "PocketDrop arrivals", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun notifyArrival(title: String, text: String) {
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            NotificationCompat.Builder(this, CHANNEL_ARRIVALS).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title).setContentText(text).setContentIntent(openApp).setAutoCancel(true).build())
    }

    private fun notifyFileArrival(name: String, path: String, mime: String) {
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val review = Intent(this, MainActivity::class.java).apply {
            action = ACTION_REVIEW_FILE
            data = Uri.parse("pocketdrop://arrival/$id")
            putExtra(EXTRA_VALUE, name)
            putExtra(EXTRA_PATH, path)
            putExtra(EXTRA_MIME, mime)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(this, id, review, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(id,
            NotificationCompat.Builder(this, CHANNEL_ARRIVALS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("File received from PC")
                .setContentText("$name · Tap to open or choose where to save")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build())
    }

    override fun onDestroy() {
        running = false
        server?.close()
        listenerWorker.shutdownNow()
        transferWorkers.shutdownNow()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 8735
        const val CHANNEL_SERVICE = "pocketdrop_receiver"
        const val CHANNEL_ARRIVALS = "pocketdrop_arrivals"
        const val ACTION_RECEIVED = "com.raytek.pocketdrop.RECEIVED"
        const val ACTION_REVIEW_FILE = "com.raytek.pocketdrop.REVIEW_FILE"
        const val EXTRA_TYPE = "type"
        const val EXTRA_VALUE = "value"
        const val EXTRA_PATH = "path"
        const val EXTRA_MIME = "mime"
        fun localAddress(): String {
            return try {
                NetworkInterface.getNetworkInterfaces().toList()
                    .sortedBy { if (it.name.contains("wlan", true) || it.displayName.contains("wifi", true)) 0 else 1 }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                    ?.hostAddress?.let { "http://$it:$PORT" } ?: ""
            } catch (_: Exception) { "" }
        }
    }
}
