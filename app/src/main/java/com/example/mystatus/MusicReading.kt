package com.example.mystatus

import android.media.session.PlaybackState
import android.app.Notification
import android.os.Bundle

enum class MusicPlaybackState { PLAYING, PAUSED, STOPPED, UNKNOWN }

fun playbackStateOf(value: Int?): MusicPlaybackState = when (value) {
    PlaybackState.STATE_PLAYING -> MusicPlaybackState.PLAYING
    PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING,
    PlaybackState.STATE_CONNECTING -> MusicPlaybackState.PAUSED
    PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE,
    PlaybackState.STATE_ERROR -> MusicPlaybackState.STOPPED
    else -> MusicPlaybackState.UNKNOWN
}

data class MusicMetadata(
    val title: String?,
    val artist: String?,
    val album: String? = null
) {
    val isUsable: Boolean get() = !title.isNullOrBlank()
}

object MusicPlayers {
    val knownPackages = linkedMapOf(
        "网易云音乐" to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",
        "酷狗音乐" to "com.kugou.android",
        "酷我音乐" to "cn.kuwo.player",
        "Spotify" to "com.spotify.music",
        "YouTube Music" to "com.google.android.apps.youtube.music"
    )

    fun isValidPackage(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
}

internal data class NotificationMusicFields(
    val title: String,
    val artist: String?,
    val album: String?
)

internal fun parseNotificationMusicFields(notification: Notification): NotificationMusicFields? {
    val extras = notification.extras ?: Bundle()
    val title = firstText(extras, Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG) ?: return null
    val artist = firstText(extras, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT)
    val album = firstText(extras, Notification.EXTRA_INFO_TEXT)
    val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
    val mediaLike = notification.category == Notification.CATEGORY_TRANSPORT ||
        extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
        template.contains("MediaStyle", ignoreCase = true)
    val ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    if (notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 && !mediaLike) return null
    if (!mediaLike && (!ongoing || artist.isNullOrBlank())) return null
    if (artist != null && artist.equals(title, ignoreCase = true)) return null
    return NotificationMusicFields(title, artist, album)
}

private fun firstText(extras: Bundle, vararg keys: String): String? =
    keys.asSequence().mapNotNull { extras.getCharSequence(it)?.toString()?.trim() }
        .firstOrNull { it.isNotBlank() }

internal data class MusicCandidate(
    val sourceId: String,
    val packageName: String,
    val state: MusicPlaybackState,
    val metadata: MusicMetadata?
)

internal fun selectMusicCandidate(candidates: Collection<MusicCandidate>): MusicCandidate? =
    candidates.sortedWith(
        compareByDescending<MusicCandidate> { it.state == MusicPlaybackState.PLAYING }
            .thenByDescending { it.state == MusicPlaybackState.PAUSED }
            .thenByDescending { it.sourceId.startsWith("notification:") }
    ).firstOrNull {
        it.state != MusicPlaybackState.STOPPED && it.metadata?.isUsable == true
    }

internal class MusicEventDeduper {
    private var last: String? = null
    fun isDuplicate(signature: String): Boolean =
        if (signature == last) true else {
            last = signature
            false
        }
}
