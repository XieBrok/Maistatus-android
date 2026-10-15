package com.example.mystatus

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

class MusicNotificationListener : NotificationListenerService() {
    private val MUSIC_PACKAGE_ALLOWLIST = setOf(
        "com.netease.cloudmusic",
        "com.tencent.qqmusic"
    )
    private val handler = Handler(Looper.getMainLooper())
    private val notifications = mutableMapOf<String, MusicCandidate>()
    private val eventDeduper = MusicEventDeduper()
    private var lastPublishedSignature: String? = null

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
            lastPublishedSignature = null
            instance = null
            StatusStore.setMusicDiagnostic(this, "通知监听服务已断开，请重新授权")
            clearMusicIfNeeded()
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isAllowedMusicPackage(sbn.packageName)) return
        handler.post { processNotification(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isAllowedMusicPackage(sbn.packageName)) return
        handler.post {
            if (notifications.remove(sbn.key) != null) recompute()
        }
    }

    private fun processNotification(sbn: StatusBarNotification) {
        updateNotification(sbn)
        recompute()
    }

    private fun updateNotification(sbn: StatusBarNotification) {
        if (!isAllowedMusicPackage(sbn.packageName)) {
            notifications.remove(sbn.key)
            return
        }
        val fields = runCatching { parseNotificationMusicFields(sbn.notification) }.getOrNull()
        Log.i(
            TAG,
            "Notification package=${sbn.packageName}, " +
                "parsedTitle=${fields?.title}, parsedArtist=${fields?.artist}, " +
                "state=${fields?.state ?: "ignored"}, basis=${fields?.let { stateBasis(sbn.notification, it.state) } ?: "not_music"}"
        )
        if (fields == null) {
            notifications.remove(sbn.key)
        } else {
            notifications[sbn.key] = MusicCandidate(
                sourceId = "notification:${sbn.key}",
                packageName = sbn.packageName,
                state = fields.state,
                metadata = MusicMetadata(fields.title, fields.artist, fields.album)
            )
        }
    }

    private fun isAllowedMusicPackage(packageName: String): Boolean =
        packageName in MUSIC_PACKAGE_ALLOWLIST

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
        if (signature == lastPublishedSignature) return
        lastPublishedSignature = signature
        val duplicate = eventDeduper.isDuplicate(signature)
        val previousTitle = StatusStore.musicTitle(this)
        val previousArtist = StatusStore.musicArtist(this)
        val previousState = StatusStore.musicState(this)
        Log.i(
            TAG,
            "Music state update before=[$previousTitle, $previousArtist, $previousState], " +
                "package=${candidate.packageName}, " +
                "title=${metadata.title}, artist=${metadata.artist}, state=$stateValue"
        )
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
        Log.i(
            TAG,
            "Music state update after=[${StatusStore.musicTitle(this)}, " +
                "${StatusStore.musicArtist(this)}, ${StatusStore.musicState(this)}]"
        )
        if (!duplicate) {
            EventQueue.enqueue(this, "music", JSONObject().apply {
                put("state", stateValue)
                if (!metadata.title.isNullOrBlank()) put("title", metadata.title)
                if (!metadata.artist.isNullOrBlank()) put("artist", metadata.artist)
                if (!metadata.album.isNullOrBlank()) put("album", metadata.album)
                if (candidate.packageName.isNotBlank()) put("package", candidate.packageName)
            })
        }
    }

    private fun clearMusicIfNeeded() {
        val hadPersistedMusic = lastPublishedSignature != null ||
            !StatusStore.musicTitle(this).isNullOrBlank() ||
            !StatusStore.music(this).isNullOrBlank()
        if (!hadPersistedMusic) return
        Log.i(TAG, "Music state update cleared: no reliable music notification remains")
        lastPublishedSignature = null
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
