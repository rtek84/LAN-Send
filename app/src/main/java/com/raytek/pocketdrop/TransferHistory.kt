package com.raytek.pocketdrop

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TransferHistory {
    private const val PREFS = "pocketdrop_history"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 50

    @Synchronized
    fun add(context: Context, description: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val updated = JSONArray()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        updated.put("$time  $description")
        for (index in 0 until minOf(existing.length(), MAX_ENTRIES - 1)) updated.put(existing.optString(index))
        prefs.edit().putString(KEY, updated.toString()).apply()
    }

    @Synchronized
    fun displayText(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        if (entries.length() == 0) return "No transfers yet"
        return (0 until entries.length()).joinToString("\n") { entries.optString(it) }
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
