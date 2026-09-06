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

data class PairedPcUpsertResult(
    val record: PairedPcRecord,
    val added: Boolean
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

        if (index >= 0 && deviceId.isNotBlank()) {
            val active = records[index]
            if (active.deviceId.isNotBlank() && active.deviceId != deviceId) {
                val existingNewDevice = records.indexOfFirst { it.deviceId == deviceId }
                if (existingNewDevice >= 0) {
                    index = existingNewDevice
                    activeRecordId = records[index].recordId
                } else if (records.size < MAX_REMEMBERED_PCS) {
                    val added = PairedPcRecord(
                        recordId = "pc:$deviceId",
                        deviceId = deviceId,
                        name = nextDefaultName(records),
                        server = server,
                        token = token
                    )
                    records += added
                    index = records.lastIndex
                    activeRecordId = added.recordId
                } else {
                    writeRecordsAndActive(prefs, records, active)
                    return
                }
            }
        }

        if (index < 0 && deviceId.isNotBlank()) {
            index = records.indexOfFirst { it.deviceId == deviceId }
        }

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
                name = nextDefaultName(records),
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

    @Synchronized
    fun addOrActivate(
        context: Context,
        server: String,
        token: String,
        deviceId: String
    ): PairedPcUpsertResult? {
        val cleanServer = server.trim().trimEnd('/')
        val cleanToken = token.trim()
        val cleanDeviceId = deviceId.trim()
        if (cleanServer.isBlank() || cleanToken.isBlank() || cleanDeviceId.isBlank()) return null

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val records = readRecords(prefs).toMutableList()
        val index = records.indexOfFirst { it.deviceId == cleanDeviceId }
        val added = index < 0

        val record = if (index >= 0) {
            records[index].copy(server = cleanServer, token = cleanToken, deviceId = cleanDeviceId).also {
                records[index] = it
            }
        } else {
            if (records.size >= MAX_REMEMBERED_PCS) return null
            PairedPcRecord(
                recordId = "pc:$cleanDeviceId",
                deviceId = cleanDeviceId,
                name = nextDefaultName(records),
                server = cleanServer,
                token = cleanToken
            ).also { records += it }
        }

        writeRecordsAndActive(prefs, records, record)
        return PairedPcUpsertResult(record, added)
    }

    @Synchronized
    fun select(context: Context, recordId: String): PairedPcRecord? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val records = readRecords(prefs)
        val record = records.firstOrNull { it.recordId == recordId } ?: return null
        writeRecordsAndActive(prefs, records, record)
        return record
    }

    @Synchronized
    fun rename(context: Context, recordId: String, name: String): Boolean {
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val records = readRecords(prefs).toMutableList()
        val index = records.indexOfFirst { it.recordId == recordId }
        if (index < 0) return false
        records[index] = records[index].copy(name = cleanName)
        prefs.edit().putString(RECORDS_KEY, encodeRecords(records)).apply()
        return true
    }

    @Synchronized
    fun forget(context: Context, recordId: String): PairedPcRecord? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val records = readRecords(prefs).toMutableList()
        val activeId = prefs.getString(ACTIVE_RECORD_KEY, "").orEmpty()
        val removed = records.removeAll { it.recordId == recordId }
        if (!removed) return activeRecord(context)

        if (activeId == recordId) {
            val next = records.firstOrNull()
            if (next == null) {
                prefs.edit()
                    .putString(RECORDS_KEY, encodeRecords(records))
                    .remove(ACTIVE_RECORD_KEY)
                    .remove("server")
                    .remove("token")
                    .remove("paired_pc_id")
                    .apply()
                return null
            }
            writeRecordsAndActive(prefs, records, next)
            return next
        }

        prefs.edit().putString(RECORDS_KEY, encodeRecords(records)).apply()
        return records.firstOrNull { it.recordId == activeId }
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

    fun isRememberedDevice(context: Context, deviceId: String): Boolean {
        val cleanDeviceId = deviceId.trim()
        if (cleanDeviceId.isBlank()) return false
        val remembered = records(context)
        if (remembered.any { it.deviceId == cleanDeviceId }) return true
        if (remembered.isNotEmpty()) return false
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("paired_pc_id", "").orEmpty() == cleanDeviceId
    }

    private fun writeRecordsAndActive(
        prefs: SharedPreferences,
        records: List<PairedPcRecord>,
        active: PairedPcRecord
    ) {
        prefs.edit()
            .putString(RECORDS_KEY, encodeRecords(records))
            .putString(ACTIVE_RECORD_KEY, active.recordId)
            .putString("server", active.server)
            .putString("token", active.token)
            .putString("paired_pc_id", active.deviceId)
            .apply()
    }

    private fun nextDefaultName(records: List<PairedPcRecord>): String {
        val used = records.map { it.name }.toSet()
        if ("PC" !in used) return "PC"
        for (number in 2..MAX_REMEMBERED_PCS) {
            val candidate = "PC $number"
            if (candidate !in used) return candidate
        }
        return "PC ${records.size + 1}"
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
