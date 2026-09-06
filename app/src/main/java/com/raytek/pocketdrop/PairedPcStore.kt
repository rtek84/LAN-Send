package com.raytek.pocketdrop

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PairedPcRecord(
    val recordId: String,
    val deviceId: String,
    val name: String,
    val server: String,
    val token: String
)

object PairedPcStore {
    const val MAX_REMEMBERED_PCS = 3

    private const val PREFS_NAME = "pocketdrop"
    private const val RECORDS_KEY = "paired_pcs_v1"
    private const val ACTIVE_RECORD_KEY = "active_pc_record_id"

    private val legacyPairingKeys = setOf("server", "token", "paired_pc_id")

    fun isLegacyPairingKey(key: String?): Boolean = key != null && key in legacyPairingKeys

    @Synchronized
    fun synchronizeLegacyPairing(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val server = prefs.getString("server", "").orEmpty().trim()
        val token = prefs.getString("token", "").orEmpty().trim()
        val deviceId = prefs.getString("paired_pc_id", "").orEmpty().trim()

        val records = readRecords(prefs).toMutableList()
        var activeRecordId = prefs.getString(ACTIVE_RECORD_KEY, "").orEmpty()

        if (server.isBlank() || token.isBlank()) {
            if (activeRecordId.isNotBlank()) {
                records.removeAll { it.recordId == activeRecordId }
                prefs.edit()
                    .putString(RECORDS_KEY, encodeRecords(records))
                    .remove(ACTIVE_RECORD_KEY)
                    .apply()
            } else if (!prefs.contains(RECORDS_KEY)) {
                prefs.edit().putString(RECORDS_KEY, "[]").apply()
            }
            return
        }

        var index = if (activeRecordId.isNotBlank()) {
            records.indexOfFirst { it.recordId == activeRecordId }
        } else {
            -1
        }

        if (index < 0 && deviceId.isNotBlank()) {
            index = records.indexOfFirst { it.deviceId == deviceId }
        }

        // First upgrade from the legacy single-PC format: if exactly one
        // remembered record exists, keep updating that record rather than
        // creating a duplicate while the app still uses the legacy UI.
        if (index < 0 && activeRecordId.isBlank() && records.size == 1) {
            index = 0
        }

        if (index >= 0) {
            val existing = records[index]
            val updated = existing.copy(
                deviceId = deviceId,
                server = server,
                token = token
            )
            records[index] = updated
            activeRecordId = updated.recordId
        } else {
            if (records.size >= MAX_REMEMBERED_PCS) return
            val recordId = if (deviceId.isNotBlank()) {
                "pc:$deviceId"
            } else {
                "pc:${UUID.randomUUID().toString().replace("-", "")}"
            }
            records += PairedPcRecord(
                recordId = recordId,
                deviceId = deviceId,
                name = "PC",
                server = server,
                token = token
            )
            activeRecordId = recordId
        }

        prefs.edit()
            .putString(RECORDS_KEY, encodeRecords(records))
            .putString(ACTIVE_RECORD_KEY, activeRecordId)
            .apply()
    }

    fun records(context: Context): List<PairedPcRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return readRecords(prefs)
    }

    fun activeRecord(context: Context): PairedPcRecord? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeRecordId = prefs.getString(ACTIVE_RECORD_KEY, "").orEmpty()
        if (activeRecordId.isBlank()) return null
        return readRecords(prefs).firstOrNull { it.recordId == activeRecordId }
    }

    private fun readRecords(prefs: SharedPreferences): List<PairedPcRecord> {
        val raw = prefs.getString(RECORDS_KEY, "[]").orEmpty()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val recordId = item.optString("recordId")
                    val server = item.optString("server")
                    val token = item.optString("token")
                    if (recordId.isBlank() || server.isBlank() || token.isBlank()) continue
                    add(
                        PairedPcRecord(
                            recordId = recordId,
                            deviceId = item.optString("deviceId"),
                            name = item.optString("name").ifBlank { "PC" },
                            server = server,
                            token = token
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeRecords(records: List<PairedPcRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().apply {
                put("recordId", record.recordId)
                put("deviceId", record.deviceId)
                put("name", record.name)
                put("server", record.server)
                put("token", record.token)
            })
        }
        return array.toString()
    }
}
