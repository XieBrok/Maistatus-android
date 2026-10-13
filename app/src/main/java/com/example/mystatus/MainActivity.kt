package com.example.mystatus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private val locationRequest = 101
    private val activityRequest = 102
    private val notificationRequest = 103
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        layout.addView(TextView(this).apply { text = "MyStatus"; textSize = 20f })
        val url = EditText(this).apply {
            hint = "服务器 HTTPS URL"
            setText(StatusStore.serverUrl(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        layout.addView(url)
        val credential = EditText(this).apply {
            hint = "设备写入凭证（不会写入日志）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(StatusStore.deviceCredential(this@MainActivity))
        }
        layout.addView(credential)
        val players = EditText(this).apply {
            hint = "允许的播放器包名（逗号分隔）"
            setText(StatusStore.allowedMusicPlayers(this@MainActivity).joinToString(","))
        }
        layout.addView(players)
        layout.addView(Button(this).apply {
            text = "保存上传配置"
            setOnClickListener {
                val value = url.text.toString().trim()
                if (!value.startsWith("https://", true)) {
                    status.text = "服务器地址必须使用 HTTPS"
                } else {
                    StatusStore.setServerUrl(this@MainActivity, value)
                    StatusStore.setDeviceCredential(this@MainActivity, credential.text.toString())
                    StatusStore.setAllowedMusicPlayers(this@MainActivity,
                        players.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                    status.text = "配置已保存"
                    UploadScheduler.enqueue(this@MainActivity)
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "申请定位权限"
            setOnClickListener { requestLocationPermission() }
        })
        layout.addView(Button(this).apply {
            text = "申请活动识别权限"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 29) ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), activityRequest)
            }
        })
        layout.addView(Button(this).apply {
            text = "开启音乐通知访问"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        if (Build.VERSION.SDK_INT >= 33) {
            layout.addView(Button(this).apply {
                text = "申请状态通知权限"
                setOnClickListener {
                    ActivityCompat.requestPermissions(
                        this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequest)
                }
            })
        }
        layout.addView(Button(this).apply {
            text = "开始采集"
            setOnClickListener { startCollection() }
        })
        layout.addView(Button(this).apply {
            text = "暂停/恢复定位"
            setOnClickListener {
                val action = if (StatusStore.isLocationPaused(this@MainActivity))
                    StatusService.ACTION_RESUME_LOCATION else StatusService.ACTION_PAUSE_LOCATION
                startService(Intent(this@MainActivity, StatusService::class.java).setAction(action))
                refreshStatus()
            }
        })
        layout.addView(Button(this).apply {
            text = "停止采集"
            setOnClickListener {
                startService(Intent(this@MainActivity, StatusService::class.java)
                    .setAction(StatusService.ACTION_STOP))
                status.text = "已请求停止采集"
            }
        })
        status = TextView(this)
        layout.addView(status)
        layout.addView(TextView(this).apply {
            text = "音乐仅读取你填写的播放器包名；位置和活动分别授权，拒绝非关键权限不会关闭其他功能。"
            textSize = 14f
        })
        setContentView(layout)
        refreshStatus()
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        ), locationRequest)
    }

    private fun startCollection() {
        val locationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!locationGranted) {
            status.text = "未获得定位权限；请先申请定位，活动和音乐仍可独立使用"
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, StatusService::class.java))
        status.text = "采集已启动"
    }

    private fun refreshStatus() {
        val location = if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED) "精确" else if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) "大致" else "未授权"
        val activity = if (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) "已授权" else "未授权"
        status.text = "定位：$location；活动：$activity；定位状态：${
            if (StatusStore.isLocationPaused(this)) "已暂停" else "可运行"
        }；当前音乐：${StatusStore.music(this) ?: "无"}；最近更新：${
            StatusStore.lastUpdate(this).takeIf { it > 0 }?.let {
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
            } ?: "无"}"
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }
}
