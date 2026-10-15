package com.example.mystatus

import android.app.Notification
import android.os.Bundle

enum class MusicPlaybackState { PLAYING, PAUSED, STOPPED, UNKNOWN }

data class MusicMetadata(
    val title: String?,
    val artist: String?,
    val album: String? = null
) {
    val isUsable: Boolean get() = !title.isNullOrBlank()
}

internal data class NotificationMusicFields(
    val title: String,
    val artist: String?,
    val album: String?,
    val state: MusicPlaybackState
)

/**
 * Reads only notification content. The package name is retained as source metadata
 * for the existing event format, never as an allowlist or a music classifier.
 */
internal fun parseNotificationMusicFields(notification: Notification): NotificationMusicFields? {
    val extras = notification.extras ?: Bundle()
    val title = firstText(extras, Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG) ?: return null
    val text = firstText(extras, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
    val subText = firstText(extras, Notification.EXTRA_SUB_TEXT)
    val album = firstText(extras, Notification.EXTRA_INFO_TEXT)
    val template = firstText(extras, Notification.EXTRA_TEMPLATE).orEmpty()
    val mediaLike = notification.category == Notification.CATEGORY_TRANSPORT ||
        extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
        template.contains("MediaStyle", ignoreCase = true)
    val category = notification.category.orEmpty()
    val ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    val blockedCategory = category == Notification.CATEGORY_MESSAGE ||
        category == Notification.CATEGORY_PROGRESS ||
        category == Notification.CATEGORY_ERROR
    val combined = listOfNotNull(title, text, subText).joinToString(" ")
    val blockedText = listOf("下载", "download", "正在安装", "installing", "更新中", "update")
        .any { combined.contains(it, ignoreCase = true) }

    if (!mediaLike && (blockedCategory || blockedText || !ongoing || text.isNullOrBlank())) return null
    if (text.equals(title, ignoreCase = true)) return null

    val state = when {
        listOfNotNull(text, subText, title).any {
            it.contains("暂停", ignoreCase = true) ||
                it.contains("paused", ignoreCase = true)
        } -> MusicPlaybackState.PAUSED
        ongoing -> MusicPlaybackState.PLAYING
        else -> MusicPlaybackState.UNKNOWN
    }
    return NotificationMusicFields(title, text, album ?: subText, state)
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
            .thenByDescending { it.state == MusicPlaybackState.UNKNOWN }
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
