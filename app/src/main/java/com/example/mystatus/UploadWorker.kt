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

object UploadPolicy {
    fun isAllowedEndpoint(endpoint: String): Boolean =
        try { URL(endpoint).protocol == "https" } catch (_: java.net.MalformedURLException) { false }
}

class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val endpoint = StatusStore.serverUrl(applicationContext)
        val credential = StatusStore.deviceCredential(applicationContext)
        if (endpoint.isBlank() || credential.isBlank()) return Result.failure()
        val url = try { URL(endpoint) } catch (_: MalformedURLException) { return Result.failure() }
        if (!UploadPolicy.isAllowedEndpoint(endpoint)) return Result.failure()

        val dao = AppDatabase.get(applicationContext).eventDao()
        return try {
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
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    if (code in 200..299) dao.delete(event.eventId)
                    else if (code == 408 || code == 429 || code >= 500) return Result.retry()
                    else return Result.failure()
                } finally {
                    connection.disconnect()
                }
            }
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
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.KEEP,
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
