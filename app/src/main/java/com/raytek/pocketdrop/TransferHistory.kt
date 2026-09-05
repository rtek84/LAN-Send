package com.raytek.pocketdrop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TransferHistoryEntry(val display: String, val kind: String = "", val value: String = "")

object TransferHistory {
    private const val PREFS = "pocketdrop_history"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 50

    @Synchronized
    fun add(context: Context, description: String, kind: String = "", value: String = "") {
        val existing = raw(context)
        val updated = JSONArray()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        updated.put(JSONObject().apply {
            put("display", "$time  $description")
            put("kind", kind)
            put("value", value)
        })
        for (index in 0 until minOf(existing.length(), MAX_ENTRIES - 1)) updated.put(existing.get(index))
        save(context, updated)
    }

    @Synchronized
    fun entries(context: Context): List<TransferHistoryEntry> {
        val array = raw(context)
        return (0 until array.length()).map { index ->
            when (val item = array.opt(index)) {
                is JSONObject -> TransferHistoryEntry(
                    item.optString("display"), item.optString("kind"), item.optString("value")
                )
                else -> TransferHistoryEntry(item?.toString().orEmpty())
            }
        }
    }

    @Synchronized
    fun removeAt(context: Context, removeIndex: Int) {
        val existing = raw(context)
        val updated = JSONArray()
        for (index in 0 until existing.length()) if (index != removeIndex) updated.put(existing.get(index))
        save(context, updated)
    }

    @Synchronized
    fun replaceValue(context: Context, oldValue: String, newValue: String) {
        val existing = raw(context)
        var changed = false
        for (index in 0 until existing.length()) {
            val item = existing.optJSONObject(index) ?: continue
            if (item.optString("value") == oldValue) {
                item.put("value", newValue)
                changed = true
            }
        }
        if (changed) save(context, existing)
    }

    fun displayText(context: Context, limit: Int = MAX_ENTRIES): String {
        val entries = entries(context)
        if (entries.isEmpty()) return "No transfers yet"
        return entries.take(limit).joinToString("\n") { it.display }
    }

    fun count(context: Context): Int = raw(context).length()

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun raw(context: Context): JSONArray {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        return try { JSONArray(value) } catch (_: Exception) { JSONArray() }
    }

    private fun save(context: Context, entries: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, entries.toString()).apply()
    }
}
