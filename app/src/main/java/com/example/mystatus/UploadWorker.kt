package com.example.mystatus

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class UploadOutcome {
    /** Server accepted the event; remove it from the queue. */
    DELIVERED,
    /** Transient server/network condition; keep the queue and retry with backoff. */
    RETRY,
    /** Configuration problem (bad token, wrong endpoint); keep the queue and stop until reconfigured. */
    HALT,
    /** The event itself was rejected as invalid; drop only this event. */
    DROP
}

object UploadPolicy {
    fun isAllowedEndpoint(endpoint: String): Boolean =
        try { URL(endpoint).protocol == "https" } catch (_: java.net.MalformedURLException) { false }

    fun classify(code: Int): UploadOutcome = when {
        code in 200..299 -> UploadOutcome.DELIVERED
        code == 408 || code == 429 || code >= 500 -> UploadOutcome.RETRY
        // Auth or routing failures affect every event equally. Dropping events here would
        // silently wipe the whole queue when the token is wrong or the server is misconfigured.
        code == 401 || code == 403 || code == 404 || code == 405 -> UploadOutcome.HALT
        code in 400..499 -> UploadOutcome.DROP
        // 1xx/3xx are unexpected for this API (redirects are not followed for POST bodies).
        else -> UploadOutcome.RETRY
    }
}

class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val endpoint = StatusStore.serverUrl(applicationContext)
        val credential = StatusStore.deviceCredential(applicationContext)
        if (endpoint.isBlank() || credential.isBlank()) return Result.failure()
        val eventsEndpoint = ServerUrls.eventsUrl(endpoint) ?: return Result.failure()
        val url = try { URL(eventsEndpoint) } catch (_: MalformedURLException) { return Result.failure() }
        val heartbeatEndpoint = ServerUrls.heartbeatUrl(endpoint) ?: return Result.failure()
        val heartbeatUrl = try { URL(heartbeatEndpoint) } catch (_: MalformedURLException) { return Result.failure() }
        if (!UploadPolicy.isAllowedEndpoint(eventsEndpoint) || !UploadPolicy.isAllowedEndpoint(heartbeatEndpoint)) {
            return Result.failure()
        }

        val dao = AppDatabase.get(applicationContext).eventDao()
        val deviceId = StatusStore.deviceId(applicationContext)
        val deviceName = StatusStore.deviceName()
        return try {
            // Best-effort heartbeat means an idle device can still appear recently active.
            // A failed heartbeat must not prevent queued status events from being uploaded.
            try {
                val heartbeat = (heartbeatUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $credential")
                }
                try {
                    val heartbeatBody = JSONObject()
                        .put("device_id", deviceId)
                        .put("device_name", deviceName)
                        .put("platform", "android")
                    heartbeat.outputStream.use { it.write(heartbeatBody.toString().toByteArray(Charsets.UTF_8)) }
                    heartbeat.responseCode
                } finally {
                    heartbeat.disconnect()
                }
            } catch (_: IOException) {
                // Uploads below may still succeed; the next worker run retries the heartbeat.
            }

            dao.pending(50).forEach { event ->
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $credential")
                    setRequestProperty("Idempotency-Key", event.eventId)
                }
                try {
                    val body = JSONObject(event.payload)
                        .put("event_id", event.eventId)
                        .put("event_time", event.occurredAt)
                        .put("event_type", event.type)
                        .put("device_id", deviceId)
                        .put("device_name", deviceName)
                        .put("platform", "android")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    when (UploadPolicy.classify(connection.responseCode)) {
                        UploadOutcome.DELIVERED -> {
                            dao.delete(event.eventId)
                            StatusStore.setLastSuccessfulUpload(
                                applicationContext,
                                System.currentTimeMillis()
                            )
                        }
                        UploadOutcome.RETRY -> return Result.retry()
                        // Keep every queued event; saving new settings or the periodic job retries.
                        UploadOutcome.HALT -> return Result.failure()
                        UploadOutcome.DROP -> dao.delete(event.eventId)
                    }
                } finally {
                    connection.disconnect()
                }
            }
            if (dao.count() > 0) UploadScheduler.enqueueRemaining(applicationContext)
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        }
    }
}

object UploadScheduler {
    private const val PERIODIC = "status-upload-periodic"
    private const val ONCE = "status-upload-once"

    fun enqueue(context: Context) {
        enqueue(context, ExistingWorkPolicy.KEEP)
    }

    fun enqueueRemaining(context: Context) {
        // Plain APPEND chains onto the current request; if that request ends in failure or is
        // cancelled, the appended one fails too. APPEND_OR_REPLACE starts a fresh chain instead.
        enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE, policy,
            OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints).build()
        )
    }

    fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints).build()
        )
    }
}
