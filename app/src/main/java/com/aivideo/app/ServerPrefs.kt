package com.aivideo.app

import android.content.Context
import org.json.JSONArray

object ServerPrefs {
    private const val PREF_NAME = "aivideo_prefs"
    private const val KEY_CURRENT_URL = "current_url"
    private const val KEY_HISTORY = "history_urls"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_IGNORE_SSL = "ignore_ssl"
    private const val MAX_HISTORY = 8

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getCurrentUrl(context: Context): String {
        return prefs(context).getString(KEY_CURRENT_URL, "") ?: ""
    }

    fun saveServer(context: Context, url: String, keepScreenOn: Boolean, ignoreSsl: Boolean) {
        val history = getHistory(context).toMutableList()
        history.remove(url)
        history.add(0, url)
        val trimmed = history.take(MAX_HISTORY)

        val json = JSONArray()
        trimmed.forEach { json.put(it) }

        prefs(context).edit()
            .putString(KEY_CURRENT_URL, url)
            .putString(KEY_HISTORY, json.toString())
            .putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn)
            .putBoolean(KEY_IGNORE_SSL, ignoreSsl)
            .apply()
    }

    fun getHistory(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i -> arr.getString(i) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun removeFromHistory(context: Context, url: String) {
        val history = getHistory(context).toMutableList()
        history.remove(url)
        val json = JSONArray()
        history.forEach { json.put(it) }
        prefs(context).edit().putString(KEY_HISTORY, json.toString()).apply()
    }

    fun isKeepScreenOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, true)

    fun isIgnoreSsl(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IGNORE_SSL, true)
}
