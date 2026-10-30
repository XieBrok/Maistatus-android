package com.example.mystatus

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

class MusicNotificationListener : NotificationListenerService() {
    /**
     * Not an allowlist: every app's media-style notification is accepted. These players are
     * only granted the looser heuristic for custom notifications without standard media markers.
     */
    private val HEURISTIC_PLAYER_PACKAGES = setOf(
        "com.netease.cloudmusic",
        "com.tencent.qqmusic"
    )
    private val handler = Handler(Looper.getMainLooper())
    private val notifications = mutableMapOf<String, MusicCandidate>()
    private val eventDeduper = MusicEventDeduper()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        handler.post {
            StatusStore.setMusicDiagnostic(this, "已连接通知监听器，正在检测音乐通知")
            runCatching {
                notifications.clear()
                getActiveNotifications().orEmpty().forEach(::updateNotification)
            }
                .onFailure {
                    StatusStore.setMusicDiagnostic(this, "读取活动通知失败")
                    Log.w(TAG, "Unable to read active notifications", it)
                }
            recompute()
        }
    }

    override fun onListenerDisconnected() {
        handler.post {
            notifications.clear()
            instance = null
            StatusStore.setMusicDiagnostic(this, "通知监听服务已断开，请重新授权")
            clearMusicIfNeeded()
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Ignore our own foreground-service notification.
        if (sbn.packageName == packageName) return
        handler.post { if (updateNotification(sbn)) recompute() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        handler.post {
            if (notifications.remove(sbn.key) != null) recompute()
        }
    }

    /** Returns true when the tracked music candidates changed. */
    private fun updateNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val fields = runCatching {
            parseNotificationMusicFields(
                sbn.notification,
                heuristicFallback = sbn.packageName in HEURISTIC_PLAYER_PACKAGES
            )
        }.getOrNull()
        if (fields == null) {
            // Non-music notifications (chat etc.) are never logged or stored.
            return notifications.remove(sbn.key) != null
        }
        Log.i(
            TAG,
            "Music notification package=${sbn.packageName}, " +
                "state=${fields.state}, basis=${stateBasis(sbn.notification, fields.state)}"
        )
        val candidate = MusicCandidate(
            sourceId = "notification:${sbn.key}",
            packageName = sbn.packageName,
            state = fields.state,
            metadata = MusicMetadata(fields.title, fields.artist, fields.album)
        )
        return notifications.put(sbn.key, candidate) != candidate
    }

    private fun recompute() {
        val selected = selectMusicCandidate(notifications.values)
        if (selected == null) {
            StatusStore.setMusicDiagnostic(
                this,
                if (notifications.isEmpty()) "权限已授予，但尚未获取到音乐通知"
                else "已获取通知，但没有可用的音乐字段"
            )
            clearMusicIfNeeded()
            return
        }
        publish(selected)
    }

    private fun publish(candidate: MusicCandidate) {
        val metadata = candidate.metadata ?: return
        val stateValue = candidate.state.name.lowercase()
        val signature = "$stateValue|${candidate.packageName}|" +
            "${metadata.title}|${metadata.artist}|${metadata.album}"
        if (eventDeduper.isDuplicate(signature)) return
        Log.i(TAG, "Music state update package=${candidate.packageName}, state=$stateValue")
        StatusStore.setMusicReading(
            this,
            candidate.packageName,
            metadata.title,
            metadata.artist,
            metadata.album,
            stateValue,
            if (candidate.state == MusicPlaybackState.PAUSED) "通知显示已暂停"
            else if (metadata.artist.isNullOrBlank()) "已读取歌曲，但艺术家元数据缺失"
            else "已成功读取歌曲"
        )
        EventQueue.enqueue(this, "music", JSONObject().apply {
            put("state", stateValue)
            if (!metadata.title.isNullOrBlank()) put("title", metadata.title)
            if (!metadata.artist.isNullOrBlank()) put("artist", metadata.artist)
            if (!metadata.album.isNullOrBlank()) put("album", metadata.album)
            if (candidate.packageName.isNotBlank()) put("package", candidate.packageName)
        })
    }

    private fun clearMusicIfNeeded() {
        // Forget the last published song so it is reported again if it reappears; otherwise the
        // server would keep the "unknown" state sent below while the same song plays again.
        eventDeduper.reset()
        val hadPersistedMusic = !StatusStore.musicTitle(this).isNullOrBlank() ||
            !StatusStore.music(this).isNullOrBlank()
        if (!hadPersistedMusic) return
        Log.i(TAG, "Music state update cleared: no reliable music notification remains")
        StatusStore.setMusicReading(
            this, null, null, null, null, MusicPlaybackState.UNKNOWN.name.lowercase(),
            "当前没有可靠的音乐通知"
        )
        EventQueue.enqueue(this, "music", JSONObject().put("state", MusicPlaybackState.UNKNOWN.name.lowercase()))
    }

    override fun onDestroy() {
        notifications.clear()
        handler.removeCallbacksAndMessages(null)
        instance = null
        super.onDestroy()
    }

    private fun stateBasis(notification: Notification, state: MusicPlaybackState): String =
        when (state) {
            MusicPlaybackState.PAUSED -> "pause marker in standard notification fields"
            MusicPlaybackState.PLAYING -> when {
                notification.category == Notification.CATEGORY_TRANSPORT -> "transport category"
                notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) -> "media session"
                else -> "ongoing notification"
            }
            MusicPlaybackState.STOPPED -> "explicit stopped state"
            MusicPlaybackState.UNKNOWN -> "no reliable playback marker"
        }

    companion object {
        private const val TAG = MUSIC_LOG_TAG
        @Volatile private var instance: MusicNotificationListener? = null

        fun refreshActiveNotifications() {
            instance?.handler?.post {
                runCatching {
                    instance?.getActiveNotifications().orEmpty().forEach { instance?.updateNotification(it) }
                    instance?.recompute()
                }.onFailure { service ->
                    instance?.let { StatusStore.setMusicDiagnostic(it, "刷新音乐通知失败") }
                    Log.w(TAG, "Unable to refresh active notifications", service)
                }
            }
        }
    }
}
