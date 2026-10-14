package com.example.mystatus

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.location.Geocoder
import android.location.Location
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

class StatusService : Service() {
    private val channelId = "status_collection"
    private lateinit var activityClient: ActivityRecognitionClient
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var locationRunning = false
    private val geocoderExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        if (!StatusStore.collectionEnabled(this)) {
            stopSelf()
            return
        }
        createChannel()
        startForeground(1, notification())
        activityClient = ActivityRecognition.getClient(this)
        requestActivityUpdates()
        UploadScheduler.schedulePeriodic(this)
        if (!StatusStore.isLocationPaused(this)) startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_LOCATION -> pauseLocation()
            ACTION_RESUME_LOCATION -> resumeLocation()
            ACTION_STOP -> {
                StatusStore.setCollectionEnabled(this, false)
                pauseLocation()
                if (::activityClient.isInitialized) {
                    activityClient.removeActivityUpdates(activityPendingIntent())
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun requestActivityUpdates() {
        if (!StatusStore.collectionEnabled(this)) return
        if (android.os.Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED) return
        val pendingIntent = PendingIntent.getBroadcast(
            this, 1, Intent(this, ActivityUpdateReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        activityClient.requestActivityUpdates(15 * 60_000L, pendingIntent)
    }

    private fun startLocationUpdates() {
        if (!StatusStore.collectionEnabled(this)) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        val priority = if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, 5 * 60_000L)
            .setMinUpdateIntervalMillis(2 * 60_000L)
            .setMaxUpdateDelayMillis(10 * 60_000L)
            .build()
        locationClient.requestLocationUpdates(request, locationCallback, mainLooper)
        locationRunning = true
        StatusStore.setLocationPaused(this, false)
    }

    private fun pauseLocation() {
        if (locationRunning) locationClient.removeLocationUpdates(locationCallback)
        locationRunning = false
        StatusStore.setLocationPaused(this, true)
    }

    private fun resumeLocation() {
        StatusStore.setLocationPaused(this, false)
        startLocationUpdates()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!StatusStore.collectionEnabled(this@StatusService)) return
            result.lastLocation?.let {
                resolveAddress(it)
                EventQueue.enqueue(this@StatusService, "location", JSONObject()
                    .put("latitude", it.latitude).put("longitude", it.longitude)
                    .put("accuracy_meters", it.accuracy)
                    .put("provider", it.provider ?: "unknown"))
            }
        }

        private fun resolveAddress(location: Location) {
            if (!Geocoder.isPresent()) {
                return
            }
            geocoderExecutor.execute {
                val address = runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(this@StatusService, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
                        ?.firstOrNull()?.getAddressLine(0)?.trim()
                }.getOrNull()?.takeIf { it.isNotBlank() }
                if (address != null) StatusStore.setLocationAddress(this@StatusService, address)
            }
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "状态采集", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, channelId)
            .setContentTitle("MaiStatus Android 正在采集")
            .setContentText("位置、活动和音乐将在获得授权后上报")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    override fun onDestroy() {
        if (locationRunning) locationClient.removeLocationUpdates(locationCallback)
        geocoderExecutor.shutdownNow()
        if (::activityClient.isInitialized) {
            activityClient.removeActivityUpdates(activityPendingIntent())
        }
        super.onDestroy()
    }

    private fun activityPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 1, Intent(this, ActivityUpdateReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PAUSE_LOCATION = "com.example.mystatus.PAUSE_LOCATION"
        const val ACTION_RESUME_LOCATION = "com.example.mystatus.RESUME_LOCATION"
        const val ACTION_STOP = "com.example.mystatus.STOP"
    }
}
