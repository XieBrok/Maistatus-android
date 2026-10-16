package com.example.mystatus

import android.content.Context
import android.os.Build
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

object UploadPolicy {
    fun isAllowedEndpoint(endpoint: String): Boolean =
        try { URL(endpoint).protocol == "https" } catch (_: java.net.MalformedURLException) { false }
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
                        .put("device_id", StatusStore.deviceId(applicationContext))
                        .put("device_name", StatusStore.deviceName())
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
                        .put("device_id", StatusStore.deviceId(applicationContext))
                        .put("device_name", StatusStore.deviceName())
                        .put("platform", "android")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    when {
                        code in 200..299 -> {
                            dao.delete(event.eventId)
                            StatusStore.setLastSuccessfulUpload(
                                applicationContext,
                                System.currentTimeMillis()
                            )
                        }
                        code == 408 || code == 429 || code >= 500 -> return Result.retry()
                        code in 400..499 -> dao.delete(event.eventId)
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
        enqueue(context, ExistingWorkPolicy.APPEND)
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
