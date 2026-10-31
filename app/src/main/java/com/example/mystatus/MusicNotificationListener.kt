package com.example.mystatus

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
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
    private val notificationControllers = mutableMapOf<String, ControllerRegistration>()
    private val activeSessionControllers = mutableMapOf<MediaSession.Token, ControllerRegistration>()
    private var sessionManager: MediaSessionManager? = null
    private val eventDeduper = MusicEventDeduper()

    private data class ControllerRegistration(
        val controller: MediaController,
        val callback: MediaController.Callback
    )

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        syncActiveSessionControllers(controllers.orEmpty())
        reloadActiveNotifications()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        handler.post {
            StatusStore.setMusicDiagnostic(this, "已连接通知监听器，正在检测音乐通知")
            registerSessionListener()
            reloadActiveNotifications()
        }
    }

    override fun onListenerDisconnected() {
        handler.post {
            unregisterAllControllers()
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
            unregisterNotificationController(sbn.key)
            if (notifications.remove(sbn.key) != null) recompute()
        }
    }

    private fun registerSessionListener() {
        runCatching {
            val component = ComponentName(this, MusicNotificationListener::class.java)
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
            sessionManager = manager
            manager.addOnActiveSessionsChangedListener(activeSessionsListener, component, handler)
            syncActiveSessionControllers(manager.getActiveSessions(component))
        }.onFailure {
            Log.w(TAG, "Unable to attach media session listener; falling back to notifications", it)
        }
    }

    private fun syncActiveSessionControllers(controllers: List<MediaController>) {
        val currentTokens = controllers.map { it.sessionToken }.toSet()
        val stale = activeSessionControllers.keys.filter { it !in currentTokens }
        stale.forEach { token ->
            activeSessionControllers.remove(token)?.let { reg ->
                runCatching { reg.controller.unregisterCallback(reg.callback) }
            }
        }
        controllers.forEach { controller ->
            val token = controller.sessionToken
            if (token in activeSessionControllers) return@forEach
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) =
                    refreshPackageNotifications(controller.packageName)

                override fun onMetadataChanged(metadata: MediaMetadata?) =
                    refreshPackageNotifications(controller.packageName)

                override fun onSessionDestroyed() {
                    activeSessionControllers.remove(token)?.let { reg ->
                        runCatching { reg.controller.unregisterCallback(reg.callback) }
                    }
                    refreshPackageNotifications(controller.packageName)
                }
            }
            runCatching { controller.registerCallback(callback, handler) }
            activeSessionControllers[token] = ControllerRegistration(controller, callback)
        }
    }

    private fun reloadActiveNotifications() {
        runCatching {
            val active = getActiveNotifications().orEmpty()
            val activeKeys = active.map { it.key }.toSet()
            notifications.keys.filter { it !in activeKeys }.forEach { key ->
                notifications.remove(key)
                unregisterNotificationController(key)
            }
            active.forEach(::updateNotification)
        }.onFailure {
            StatusStore.setMusicDiagnostic(this, "读取活动通知失败")
            Log.w(TAG, "Unable to read active notifications", it)
        }
        recompute()
    }

    private fun refreshPackageNotifications(targetPackage: String) {
        runCatching {
            var changed = false
            getActiveNotifications().orEmpty()
                .filter { it.packageName == targetPackage }
                .forEach { if (updateNotification(it)) changed = true }
            if (changed) recompute()
        }.onFailure {
            Log.w(TAG, "Unable to refresh notifications for $targetPackage", it)
        }
    }

    /** Returns true when the tracked music candidates changed. */
    private fun updateNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val sessionState = resolveSessionPlaybackState(sbn)
        val fields = runCatching {
            parseNotificationMusicFields(
                sbn.notification,
                heuristicFallback = sbn.packageName in HEURISTIC_PLAYER_PACKAGES,
                sessionPlaybackState = sessionState
            )
        }.getOrNull()
        if (fields == null) {
            unregisterNotificationController(sbn.key)
            // Non-music notifications (chat etc.) are never logged or stored.
            return notifications.remove(sbn.key) != null
        }
        Log.i(
            TAG,
            "Music notification package=${sbn.packageName}, " +
                "state=${fields.state}, basis=${stateBasis(sbn.notification, fields.state, sessionState)}"
        )
        val candidate = MusicCandidate(
            sourceId = "notification:${sbn.key}",
            packageName = sbn.packageName,
            state = fields.state,
            metadata = MusicMetadata(fields.title, fields.artist, fields.album)
        )
        return notifications.put(sbn.key, candidate) != candidate
    }

    /**
     * Resolves the authoritative [PlaybackState] code for [sbn], registering a callback on the
     * notification's `EXTRA_MEDIA_SESSION` token when present so play/pause transitions fire
     * immediately even when the player does not re-post the notification.
     */
    private fun resolveSessionPlaybackState(sbn: StatusBarNotification): Int? {
        val token = extractMediaSessionToken(sbn.notification)
        if (token != null) {
            val controller = getOrRegisterNotificationController(sbn.key, sbn.packageName, token)
            val state = runCatching { controller?.playbackState?.state }.getOrNull()
            if (state != null) return state
        } else {
            unregisterNotificationController(sbn.key)
        }
        // Fallback for custom notifications (e.g. NetEase / QQ custom style) that omit
        // EXTRA_MEDIA_SESSION on the notification but still publish an active MediaSession.
        val matchingStates = activeSessionControllers.values
            .asSequence()
            .map { it.controller }
            .filter { it.packageName == sbn.packageName }
            .mapNotNull { runCatching { it.playbackState?.state }.getOrNull() }
            .toList()
        return matchingStates.firstOrNull { playbackStateFromSessionState(it) == MusicPlaybackState.PLAYING }
            ?: matchingStates.firstOrNull { playbackStateFromSessionState(it) == MusicPlaybackState.PAUSED }
            ?: matchingStates.firstOrNull()
    }

    private fun extractMediaSessionToken(notification: Notification): MediaSession.Token? {
        val extras = notification.extras ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }
        }.getOrNull()
    }

    private fun getOrRegisterNotificationController(
        key: String,
        pkg: String,
        token: MediaSession.Token
    ): MediaController? {
        val existing = notificationControllers[key]
        if (existing != null && existing.controller.sessionToken == token) {
            return existing.controller
        }
        unregisterNotificationController(key)
        return runCatching {
            val controller = MediaController(this, token)
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) =
                    refreshPackageNotifications(pkg)

                override fun onMetadataChanged(metadata: MediaMetadata?) =
                    refreshPackageNotifications(pkg)

                override fun onSessionDestroyed() {
                    unregisterNotificationController(key)
                    refreshPackageNotifications(pkg)
                }
            }
            controller.registerCallback(callback, handler)
            notificationControllers[key] = ControllerRegistration(controller, callback)
            controller
        }.getOrNull()
    }

    private fun unregisterNotificationController(key: String) {
        notificationControllers.remove(key)?.let { reg ->
            runCatching { reg.controller.unregisterCallback(reg.callback) }
        }
    }

    private fun unregisterAllControllers() {
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        sessionManager = null
        notificationControllers.values.forEach { reg ->
            runCatching { reg.controller.unregisterCallback(reg.callback) }
        }
        notificationControllers.clear()
        activeSessionControllers.values.forEach { reg ->
            runCatching { reg.controller.unregisterCallback(reg.callback) }
        }
        activeSessionControllers.clear()
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
        unregisterAllControllers()
        notifications.clear()
        handler.removeCallbacksAndMessages(null)
        instance = null
        super.onDestroy()
    }

    private fun stateBasis(
        notification: Notification,
        state: MusicPlaybackState,
        sessionPlaybackState: Int?
    ): String = when {
        playbackStateFromSessionState(sessionPlaybackState) != null ->
            "media session state ($sessionPlaybackState)"
        playbackStateFromActions(notification.actions) != null ->
            "notification action buttons"
        state == MusicPlaybackState.PAUSED -> "pause marker or non-ongoing media notification"
        state == MusicPlaybackState.PLAYING -> when {
            notification.category == Notification.CATEGORY_TRANSPORT -> "transport category"
            notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) -> "media session"
            else -> "ongoing notification"
        }
        state == MusicPlaybackState.STOPPED -> "explicit stopped state"
        else -> "no reliable playback marker"
    }

    companion object {
        private const val TAG = MUSIC_LOG_TAG
        @Volatile private var instance: MusicNotificationListener? = null

        fun refreshActiveNotifications() {
            instance?.handler?.post {
                instance?.reloadActiveNotifications()
            }
        }
    }
}
