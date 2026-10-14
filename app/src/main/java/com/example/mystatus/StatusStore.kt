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
    private const val LAST_SUCCESSFUL_UPLOAD = "last_successful_upload"
    private const val MUSIC_STATE = "music_state"
    private const val COLLECTION_ENABLED = "collection_enabled"
    private const val MUSIC_PACKAGE = "music_package"
    private const val MUSIC_TITLE = "music_title"
    private const val MUSIC_ARTIST = "music_artist"
    private const val MUSIC_LAST_READ = "music_last_read"
    private const val MUSIC_DIAGNOSTIC = "music_diagnostic"

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
        prefs(context).getStringSet(ALLOWED_PLAYERS, emptySet())?.toSet() ?: emptySet()

    fun setAllowedMusicPlayers(context: Context, packages: Set<String>) =
        prefs(context).edit().putStringSet(ALLOWED_PLAYERS, packages.toSet()).apply()

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

    fun lastSuccessfulUpload(context: Context): Long =
        prefs(context).getLong(LAST_SUCCESSFUL_UPLOAD, 0L)

    fun setLastSuccessfulUpload(context: Context, value: Long) =
        prefs(context).edit().putLong(LAST_SUCCESSFUL_UPLOAD, value).apply()

    fun musicState(context: Context): String? = prefs(context).getString(MUSIC_STATE, null)

    fun setMusicState(context: Context, value: String?) =
        prefs(context).edit().putString(MUSIC_STATE, value).apply()

    fun collectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(COLLECTION_ENABLED, false)

    fun setCollectionEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(COLLECTION_ENABLED, value).apply()

    fun musicPackage(context: Context): String? = prefs(context).getString(MUSIC_PACKAGE, null)
    fun musicTitle(context: Context): String? = prefs(context).getString(MUSIC_TITLE, null)
    fun musicArtist(context: Context): String? = prefs(context).getString(MUSIC_ARTIST, null)
    fun musicLastRead(context: Context): Long = prefs(context).getLong(MUSIC_LAST_READ, 0L)
    fun musicDiagnostic(context: Context): String =
        prefs(context).getString(MUSIC_DIAGNOSTIC, "采集功能未启动") ?: "采集功能未启动"

    fun setMusicReading(
        context: Context,
        packageName: String?,
        title: String?,
        artist: String?,
        state: String,
        diagnostic: String,
        readAt: Long = System.currentTimeMillis()
    ) {
        prefs(context).edit()
            .putString(MUSIC_PACKAGE, packageName)
            .putString(MUSIC_TITLE, title)
            .putString(MUSIC_ARTIST, artist)
            .putString(MUSIC, listOfNotNull(title, artist?.takeIf { it.isNotBlank() }).joinToString(" - ")
                .ifBlank { null })
            .putString(MUSIC_STATE, state)
            .putString(MUSIC_DIAGNOSTIC, diagnostic)
            .putLong(MUSIC_LAST_READ, readAt)
            .apply()
    }

    fun setMusicDiagnostic(context: Context, diagnostic: String) =
        prefs(context).edit().putString(MUSIC_DIAGNOSTIC, diagnostic).apply()
}
