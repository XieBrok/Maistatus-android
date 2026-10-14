package com.example.mystatus

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
import org.json.JSONObject

class MusicNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!StatusStore.collectionEnabled(this)) return
        if (sbn.packageName !in StatusStore.allowedMusicPlayers(this)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val token: MediaSession.Token? = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            (extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token)
        }
        val controller = token?.let { MediaController(this, it) }
        val state = controller?.playbackState?.state
        val isMedia = sbn.notification.category == Notification.CATEGORY_TRANSPORT ||
            token != null || extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        if (!isMedia || title.isNullOrBlank()) return
        val playbackState = when (state) {
            android.media.session.PlaybackState.STATE_PLAYING -> "playing"
            android.media.session.PlaybackState.STATE_STOPPED,
            android.media.session.PlaybackState.STATE_NONE -> "stopped"
            else -> "paused"
        }
        val key = sbn.key
        val value = listOfNotNull(title, artist?.takeIf { it.isNotBlank() }).joinToString(" - ")
        if (StatusStore.musicKey(this) == key && StatusStore.music(this) == value &&
            StatusStore.musicState(this) == playbackState) return
        StatusStore.setMusicState(this, playbackState)
        EventQueue.enqueue(this, "music", JSONObject()
            .put("state", playbackState)
            .put("title", title).put("artist", artist ?: "")
            .put("package", sbn.packageName))
        if (playbackState == "stopped") {
            StatusStore.setMusicKey(this, null)
            StatusStore.setMusic(this, null)
        } else {
            StatusStore.setMusicKey(this, key)
            StatusStore.setMusic(this, value)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!StatusStore.collectionEnabled(this)) return
        if (sbn.packageName in StatusStore.allowedMusicPlayers(this) &&
            StatusStore.musicKey(this) == sbn.key) {
            EventQueue.enqueue(this, "music", JSONObject().put("state", "stopped")
                .put("package", sbn.packageName))
            StatusStore.setMusic(this, null)
            StatusStore.setMusicKey(this, null)
            StatusStore.setMusicState(this, "stopped")
        }
    }
}
