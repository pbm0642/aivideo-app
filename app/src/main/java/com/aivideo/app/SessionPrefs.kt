package com.aivideo.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object SessionPrefs {
    private const val PREF_NAME = "dscam_prefs"
    private const val KEY_ADDRESS = "current_address"
    private const val KEY_USERNAME = "current_username"
    private const val KEY_PASSWORD = "current_password"
    private const val KEY_HTTPS = "current_https"
    private const val KEY_TOKEN = "current_token"
    private const val KEY_REMEMBER = "remember_login"
    private const val KEY_HISTORY = "saved_accounts_json"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_IGNORE_SSL = "ignore_ssl"
    private const val KEY_LAYOUT_MODE = "layout_mode"
    private const val KEY_SHOW_INFO_BAR = "show_info_bar"
    private const val KEY_KEEP_ASPECT = "keep_aspect"
    private const val KEY_SELECTED_DEVICES = "selected_device_ids"
    private const val MAX_HISTORY = 12

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun saveSession(
        context: Context,
        account: SavedAccount,
        token: String,
        rememberLogin: Boolean
    ) {
        val list = getHistory(context).toMutableList()
        list.removeAll {
            it.displayHost().equals(account.displayHost(), ignoreCase = true) &&
                it.username == account.username
        }
        val toStore = if (rememberLogin) account else account.copy(password = "")
        list.add(0, toStore)
        val arr = JSONArray()
        list.take(MAX_HISTORY).forEach { item ->
            arr.put(JSONObject().apply {
                put("address", item.address)
                put("username", item.username)
                put("password", item.password)
                put("useHttps", item.useHttps)
            })
        }

        prefs(context).edit()
            .putString(KEY_ADDRESS, account.address)
            .putString(KEY_USERNAME, account.username)
            .putString(KEY_PASSWORD, if (rememberLogin) account.password else "")
            .putBoolean(KEY_HTTPS, account.useHttps)
            .putString(KEY_TOKEN, token)
            .putBoolean(KEY_REMEMBER, rememberLogin)
            .putString(KEY_HISTORY, arr.toString())
            .apply()
    }

    fun getCurrentAccount(context: Context): SavedAccount? {
        val p = prefs(context)
        val addr = p.getString(KEY_ADDRESS, "") ?: ""
        if (addr.isBlank()) return null
        return SavedAccount(
            address = addr,
            username = p.getString(KEY_USERNAME, "") ?: "",
            password = p.getString(KEY_PASSWORD, "") ?: "",
            useHttps = p.getBoolean(KEY_HTTPS, false)
        )
    }

    fun getToken(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "") ?: ""

    fun clearToken(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).apply()
    }

    fun isRememberLogin(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMEMBER, true)

    fun getHistory(context: Context): List<SavedAccount> {
        val raw = prefs(context).getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                SavedAccount(
                    address = obj.optString("address", ""),
                    username = obj.optString("username", ""),
                    password = obj.optString("password", ""),
                    useHttps = obj.optBoolean("useHttps", false)
                )
            }.filter { it.address.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun removeFromHistory(context: Context, account: SavedAccount) {
        val list = getHistory(context).filterNot {
            it.address == account.address && it.username == account.username
        }
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(JSONObject().apply {
                put("address", item.address)
                put("username", item.username)
                put("password", item.password)
                put("useHttps", item.useHttps)
            })
        }
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun isKeepScreenOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, true)

    fun setKeepScreenOn(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }

    fun isIgnoreSsl(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IGNORE_SSL, true)

    fun setIgnoreSsl(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_IGNORE_SSL, enabled).apply()
    }

    fun getLayoutMode(context: Context): GridLayoutMode =
        GridLayoutMode.fromName(prefs(context).getString(KEY_LAYOUT_MODE, GridLayoutMode.GRID_1X2.name))

    fun setLayoutMode(context: Context, mode: GridLayoutMode) {
        prefs(context).edit().putString(KEY_LAYOUT_MODE, mode.name).apply()
    }

    fun isShowInfoBar(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_INFO_BAR, true)

    fun setShowInfoBar(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_INFO_BAR, show).apply()
    }

    fun isKeepAspectRatio(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_ASPECT, true)

    fun setKeepAspectRatio(context: Context, keep: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_ASPECT, keep).apply()
    }

    fun getSelectedDeviceIds(context: Context): List<Int> {
        val raw = prefs(context).getString(KEY_SELECTED_DEVICES, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    fun setSelectedDeviceIds(context: Context, ids: List<Int>) {
        prefs(context).edit().putString(KEY_SELECTED_DEVICES, ids.joinToString(",")).apply()
    }
}
