package com.example.mystatus

import android.media.session.PlaybackState

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
    val isUsable: Boolean get() = !title.isNullOrBlank() && !artist.isNullOrBlank()
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

internal data class MusicCandidate(
    val sourceId: String,
    val packageName: String,
    val state: MusicPlaybackState,
    val metadata: MusicMetadata?
)

internal fun selectMusicCandidate(candidates: Collection<MusicCandidate>): MusicCandidate? =
    candidates.sortedWith(
        compareByDescending<MusicCandidate> { it.state == MusicPlaybackState.PLAYING }
            .thenByDescending { it.sourceId.startsWith("session:") }
            .thenByDescending { it.state == MusicPlaybackState.PAUSED }
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
