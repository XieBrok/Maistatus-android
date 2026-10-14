package com.example.mystatus

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

private typealias MusicReading = MusicCandidate

class MusicNotificationListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val notifications = mutableMapOf<String, MusicReading>()
    private val sessions = mutableMapOf<String, MusicReading>()
    private val callbacks = mutableMapOf<String, MediaController.Callback>()
    private val controllers = mutableMapOf<String, MediaController>()
    private var sessionManager: MediaSessionManager? = null
    private val eventDeduper = MusicEventDeduper()
    private var currentSource: String? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener {
        refreshSessions(it.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        handler.post {
            runCatching {
                sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
                sessionManager?.addOnActiveSessionsChangedListener(
                    sessionsChanged, ComponentName(this, MusicNotificationListener::class.java), handler
                )
                StatusStore.setMusicDiagnostic(this, "已连接通知监听器，正在检测音乐来源")
                refreshSessions(sessionManager?.getActiveSessions(
                    ComponentName(this, MusicNotificationListener::class.java)
                ).orEmpty())
                getActiveNotifications().orEmpty().forEach(::processNotification)
            }.onFailure {
                StatusStore.setMusicDiagnostic(this, "通知监听已连接，但媒体会话权限尚未满足")
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handler.post { processNotification(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        handler.post {
            if (notifications.remove(sbn.key) != null) recompute()
        }
    }

    private fun processNotification(sbn: StatusBarNotification) {
        if (sbn.packageName !in StatusStore.allowedMusicPlayers(this)) return
        val extras = sbn.notification.extras
        val token = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }
        }.getOrNull()
        if (sbn.notification.category != Notification.CATEGORY_TRANSPORT && token == null) return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val controller = token?.let { runCatching { MediaController(this, it) }.getOrNull() }
        val state = playbackStateOf(controller?.playbackState?.state)
        notifications[sbn.key] = MusicReading(
            "notification:${sbn.key}", sbn.packageName, state,
            MusicMetadata(title, artist).takeIf { !it.title.isNullOrBlank() }
        )
        recompute()
    }

    private fun refreshSessions(activeControllers: List<MediaController>) {
        callbacks.forEach { (sourceId, callback) -> this.controllers[sourceId]?.unregisterCallback(callback) }
        sessions.clear()
        callbacks.clear()
        controllers.clear()
        activeControllers.forEach { controller ->
            if (controller.packageName !in StatusStore.allowedMusicPlayers(this)) return@forEach
            val sourceId = "session:${controller.sessionToken}"
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) =
                    refreshSession(controller)
                override fun onMetadataChanged(metadata: MediaMetadata?) =
                    refreshSession(controller)
                override fun onSessionDestroyed() {
                    sessions.remove(sourceId)
                    recompute()
                }
            }
            callbacks[sourceId] = callback
            controllers[sourceId] = controller
            runCatching { controller.registerCallback(callback, handler) }
            sessions[sourceId] = readingFor(controller, sourceId)
        }
        if (activeControllers.isEmpty() && notifications.isEmpty()) {
            StatusStore.setMusicDiagnostic(this, "权限已授予，但尚未检测到活动媒体会话")
        }
        recompute()
    }

    private fun refreshSession(controller: MediaController) {
        val sourceId = "session:${controller.sessionToken}"
        if (controller.packageName !in StatusStore.allowedMusicPlayers(this)) {
            sessions.remove(sourceId)
        } else {
            sessions[sourceId] = readingFor(controller, sourceId)
        }
        recompute()
    }

    private fun readingFor(controller: MediaController, sourceId: String) =
        MusicReading(
            sourceId, controller.packageName, playbackStateOf(
                runCatching { controller.playbackState?.state }.getOrNull()
            ), runCatching {
                controller.metadata?.let {
                    MusicMetadata(
                        it.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim(),
                        (it.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            ?: it.getString(MediaMetadata.METADATA_KEY_AUTHOR))?.trim(),
                        it.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim()
                    )
                }
            }.getOrNull()
        )

    private fun recompute() {
        if (!StatusStore.collectionEnabled(this)) return
        val candidates = sessions.values.toList() + notifications.values
        val selected = selectMusicCandidate(candidates)
        if (selected == null) {
            if (currentSource != null) publish(null, MusicPlaybackState.STOPPED, null, null)
            StatusStore.setMusicDiagnostic(this, if (sessions.any { it.state != MusicPlaybackState.STOPPED })
                "已发现媒体会话，但歌曲元数据缺失" else "权限已授予，但尚未检测到活动媒体会话")
            return
        }
        publish(selected.sourceId, selected.state, selected.metadata, selected.packageName)
    }

    private fun publish(
        sourceId: String?,
        state: MusicPlaybackState,
        metadata: MusicMetadata?,
        packageName: String?
    ) {
        val stateValue = state.name.lowercase()
        val title = metadata?.title
        val artist = metadata?.artist
        val signature = "$sourceId|$stateValue|$packageName|$title|$artist"
        if (eventDeduper.isDuplicate(signature)) return
        currentSource = sourceId
        StatusStore.setMusicReading(this, packageName, title, artist, stateValue,
            if (state == MusicPlaybackState.STOPPED) "已停止播放" else if (artist.isNullOrBlank())
                "已读取歌曲，但艺术家元数据缺失" else "已成功读取歌曲")
        EventQueue.enqueue(this, "music", JSONObject().apply {
            put("state", stateValue)
            if (!title.isNullOrBlank()) put("title", title)
            if (!artist.isNullOrBlank()) put("artist", artist)
            if (!packageName.isNullOrBlank()) put("package", packageName)
        })
        if (state == MusicPlaybackState.STOPPED) currentSource = null
    }

    override fun onDestroy() {
        runCatching {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChanged)
            callbacks.forEach { (sourceId, callback) ->
                controllers[sourceId]?.unregisterCallback(callback)
            }
        }
        callbacks.clear()
        controllers.clear()
        sessions.clear()
        notifications.clear()
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: MusicNotificationListener? = null
        fun refreshConfiguredSources() {
            val service = instance ?: return
            service.handler.post {
                runCatching {
                    service.refreshSessions(service.sessionManager?.getActiveSessions(
                        ComponentName(service, MusicNotificationListener::class.java)
                    ).orEmpty())
                    service.recompute()
                }.onFailure {
                    StatusStore.setMusicDiagnostic(service, "播放器配置已保存，但媒体会话暂时不可用")
                }
            }
        }
    }

}
