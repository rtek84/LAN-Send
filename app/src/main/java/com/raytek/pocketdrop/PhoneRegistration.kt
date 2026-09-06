package com.raytek.pocketdrop

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

object PhoneRegistration {
    private val worker = Executors.newSingleThreadExecutor()

    fun registerAsync(context: Context, pc: PairedPcRecord) {
        val appContext = context.applicationContext
        worker.execute {
            try {
                val prefs = appContext.getSharedPreferences("pocketdrop", Context.MODE_PRIVATE)
                val phoneToken = prefs.getString("phone_token", null)?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString().replace("-", "").also {
                        prefs.edit().putString("phone_token", it).apply()
                    }
                val phoneDeviceId = prefs.getString("phone_device_id", null)?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString().replace("-", "").also {
                        prefs.edit().putString("phone_device_id", it).apply()
                    }
                val phoneAddress = PocketDropReceiverService.localAddress()
                if (phoneAddress.isBlank()) return@execute

                val bytes = "$phoneAddress|$phoneToken|$phoneDeviceId".toByteArray(StandardCharsets.UTF_8)
                val connection = URL(pc.server.trimEnd('/') + "/api/register").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                connection.setRequestProperty("X-PocketDrop-Token", pc.token)
                connection.setRequestProperty("X-PocketDrop-Device", phoneDeviceId)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                try {
                    connection.inputStream.close()
                } catch (_: Exception) {
                    connection.errorStream?.close()
                }
                connection.disconnect()
            } catch (_: Exception) {
                // Registration is retried when the user selects this PC again
                // or the main app starts its normal two-way registration flow.
            }
        }
    }
}
