package com.example.mystatus

import android.content.Context

object StatusStore {
    private const val PREFS = "status"
    private const val SERVER_URL = "server_url"
    private const val ACTIVITY = "activity"
    private const val MUSIC = "music"
    private const val MUSIC_KEY = "music_key"
    private const val ALLOWED_PLAYERS = "allowed_players"
    private const val DEVICE_CREDENTIAL = "device_credential"
    private const val LOCATION_PAUSED = "location_paused"
    private const val LAST_UPDATE = "last_update"
    private const val MUSIC_STATE = "music_state"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String =
        prefs(context).getString(SERVER_URL, "") ?: ""

    fun setServerUrl(context: Context, value: String) =
        prefs(context).edit().putString(SERVER_URL, value.trim()).apply()

    fun deviceCredential(context: Context): String =
        prefs(context).getString(DEVICE_CREDENTIAL, "") ?: ""

    fun setDeviceCredential(context: Context, value: String) =
        prefs(context).edit().putString(DEVICE_CREDENTIAL, value.trim()).apply()

    fun allowedMusicPlayers(context: Context): Set<String> =
        prefs(context).getStringSet(ALLOWED_PLAYERS, emptySet()) ?: emptySet()

    fun setAllowedMusicPlayers(context: Context, packages: Set<String>) =
        prefs(context).edit().putStringSet(ALLOWED_PLAYERS, packages).apply()

    fun activity(context: Context): String =
        prefs(context).getString(ACTIVITY, "UNKNOWN") ?: "UNKNOWN"

    fun setActivity(context: Context, value: String) =
        prefs(context).edit().putString(ACTIVITY, value).apply()

    fun music(context: Context): String? =
        prefs(context).getString(MUSIC, null)

    fun setMusic(context: Context, value: String?) =
        prefs(context).edit().putString(MUSIC, value).apply()

    fun musicKey(context: Context): String? =
        prefs(context).getString(MUSIC_KEY, null)

    fun setMusicKey(context: Context, value: String?) =
        prefs(context).edit().putString(MUSIC_KEY, value).apply()

    fun isLocationPaused(context: Context): Boolean =
        prefs(context).getBoolean(LOCATION_PAUSED, false)

    fun setLocationPaused(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(LOCATION_PAUSED, value).apply()

    fun lastUpdate(context: Context): Long = prefs(context).getLong(LAST_UPDATE, 0L)

    fun setLastUpdate(context: Context, value: Long) =
        prefs(context).edit().putLong(LAST_UPDATE, value).apply()

    fun musicState(context: Context): String? = prefs(context).getString(MUSIC_STATE, null)

    fun setMusicState(context: Context, value: String?) =
        prefs(context).edit().putString(MUSIC_STATE, value).apply()
}
