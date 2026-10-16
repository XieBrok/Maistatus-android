package com.example.mystatus

import android.content.Context
import android.os.Build
import java.util.UUID

object StatusStore {
    private const val PREFS = "status"
    private const val SERVER_URL = "server_url"
    private const val ACTIVITY = "activity"
    private const val MUSIC = "music"
    private const val MUSIC_KEY = "music_key"
    private const val DEVICE_CREDENTIAL = "device_credential"
    private const val DEVICE_ID = "device_id"
    private const val LOCATION_PAUSED = "location_paused"
    private const val LAST_UPDATE = "last_update"
    private const val LAST_SUCCESSFUL_UPLOAD = "last_successful_upload"
    private const val MUSIC_STATE = "music_state"
    private const val COLLECTION_ENABLED = "collection_enabled"
    private const val MUSIC_PACKAGE = "music_package"
    private const val MUSIC_TITLE = "music_title"
    private const val MUSIC_ARTIST = "music_artist"
    private const val MUSIC_ALBUM = "music_album"
    private const val MUSIC_LAST_READ = "music_last_read"
    private const val MUSIC_DIAGNOSTIC = "music_diagnostic"
    private const val LOCATION_ADDRESS = "location_address"
    private const val LOCATION_ADDRESS_TIME = "location_address_time"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String {
        val raw = prefs(context).getString(SERVER_URL, "") ?: ""
        return ServerUrls.normalizeBaseUrl(raw) ?: raw.trim()
    }

    fun setServerUrl(context: Context, value: String) =
        prefs(context).edit().putString(SERVER_URL, ServerUrls.normalizeBaseUrl(value) ?: value.trim()).apply()

    fun deviceCredential(context: Context): String =
        prefs(context).getString(DEVICE_CREDENTIAL, "") ?: ""

    fun setDeviceCredential(context: Context, value: String) =
        prefs(context).edit().putString(DEVICE_CREDENTIAL, value.trim()).apply()

    /** Stable per-install identifier; contains no hardware identifier or account data. */
    @Synchronized
    fun deviceId(context: Context): String {
        val store = prefs(context)
        val existing = store.getString(DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        store.edit().putString(DEVICE_ID, generated).commit()
        return generated
    }

    fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .take(80)
            .ifBlank { "Android device" }

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
    fun musicAlbum(context: Context): String? = prefs(context).getString(MUSIC_ALBUM, null)
    fun musicLastRead(context: Context): Long = prefs(context).getLong(MUSIC_LAST_READ, 0L)
    fun musicDiagnostic(context: Context): String =
        prefs(context).getString(MUSIC_DIAGNOSTIC, "采集功能未启动") ?: "采集功能未启动"

    fun setMusicReading(
        context: Context,
        packageName: String?,
        title: String?,
        artist: String?,
        album: String?,
        state: String,
        diagnostic: String,
        readAt: Long = System.currentTimeMillis()
    ) {
        prefs(context).edit()
            .putString(MUSIC_PACKAGE, packageName)
            .putString(MUSIC_TITLE, title)
            .putString(MUSIC_ARTIST, artist)
            .putString(MUSIC_ALBUM, album)
            .putString(MUSIC, listOfNotNull(title, artist?.takeIf { it.isNotBlank() }).joinToString(" - ")
                .ifBlank { null })
            .putString(MUSIC_STATE, state)
            .putString(MUSIC_DIAGNOSTIC, diagnostic)
            .putLong(MUSIC_LAST_READ, readAt)
            .apply()
    }

    fun setMusicDiagnostic(context: Context, diagnostic: String) =
        prefs(context).edit().putString(MUSIC_DIAGNOSTIC, diagnostic).apply()

    fun locationAddress(context: Context): String? =
        prefs(context).getString(LOCATION_ADDRESS, null)

    fun locationAddressTime(context: Context): Long =
        prefs(context).getLong(LOCATION_ADDRESS_TIME, 0L)

    fun setLocationAddress(context: Context, address: String, updatedAt: Long = System.currentTimeMillis()) =
        prefs(context).edit().putString(LOCATION_ADDRESS, address)
            .putLong(LOCATION_ADDRESS_TIME, updatedAt).apply()
}
