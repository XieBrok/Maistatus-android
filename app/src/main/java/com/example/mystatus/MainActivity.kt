package com.example.mystatus

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class DashboardState(
    val collectionEnabled: Boolean,
    val location: String,
    val locationPaused: Boolean,
    val activityPermission: Boolean,
    val notificationPermission: Boolean,
    val postNotificationPermission: Boolean,
    val music: String?,
    val musicState: String?,
    val activity: String,
    val lastUpdate: Long,
    val lastSuccessfulUpload: Long,
    val pending: Int
)

class MainActivity : ComponentActivity() {
    private val locationRequest = 101
    private val activityRequest = 102
    private val notificationRequest = 103
    private val serverConnectionViewModel by lazy {
        ViewModelProvider(this)[ServerConnectionViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyStatusTheme { MyStatusApp(serverConnectionViewModel) } }
    }

    override fun onStart() {
        super.onStart()
        serverConnectionViewModel.checkConnection()
    }

    fun requestLocationPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        ), locationRequest)
    }

    fun requestActivityPermission() {
        if (Build.VERSION.SDK_INT >= 29) ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), activityRequest)
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequest)
    }
}

@Composable
private fun MyStatusApp(serverConnectionViewModel: ServerConnectionViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val state by rememberDashboardState(context)
    val serverConnection by serverConnectionViewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        NavHost(navController, startDestination = "dashboard", Modifier.padding(padding)) {
            composable("dashboard") {
                DashboardScreen(
                    state = state,
                    serverConnection = serverConnection,
                    openSettings = { navController.navigate("settings") },
                    onCheckServer = { serverConnectionViewModel.checkConnection() },
                    onStart = { startCollection(context); scope.launch { snackbar.showSnackbar("采集已启动") } },
                    onPauseLocation = {
                        val action = if (state.locationPaused) StatusService.ACTION_RESUME_LOCATION
                        else StatusService.ACTION_PAUSE_LOCATION
                        context.startService(Intent(context, StatusService::class.java).setAction(action))
                    },
                    onStop = {
                        StatusStore.setCollectionEnabled(context, false)
                        context.startService(Intent(context, StatusService::class.java)
                            .setAction(StatusService.ACTION_STOP))
                        scope.launch { snackbar.showSnackbar("采集已停止，已排队事件仍会继续上传") }
                    }
                )
            }
            composable("settings") {
                SettingsScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onLocationPermission = { (context as MainActivity).requestLocationPermission() },
                    onActivityPermission = { (context as MainActivity).requestActivityPermission() },
                    onNotificationPermission = { (context as MainActivity).requestNotificationPermission() },
                    onNotificationSettings = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onPauseLocation = {
                        val action = if (state.locationPaused) StatusService.ACTION_RESUME_LOCATION
                        else StatusService.ACTION_PAUSE_LOCATION
                        context.startService(Intent(context, StatusService::class.java).setAction(action))
                    },
                    onSave = { url, token, players ->
                        if (!url.startsWith("https://", true)) {
                            scope.launch { snackbar.showSnackbar("服务器地址必须使用 HTTPS") }
                        } else if (players.any { !it.matches(Regex("[A-Za-z0-9_.]+")) }) {
                            scope.launch { snackbar.showSnackbar("播放器包名格式不正确") }
                        } else {
                            StatusStore.setServerUrl(context, url)
                            StatusStore.setDeviceCredential(context, token)
                            StatusStore.setAllowedMusicPlayers(context, players.toSet())
                            UploadScheduler.enqueue(context)
                            serverConnectionViewModel.checkConnection()
                            scope.launch { snackbar.showSnackbar("配置已保存") }
                        }
                    },
                    onSync = {
                        UploadScheduler.enqueue(context)
                        scope.launch { snackbar.showSnackbar("已加入同步队列") }
                    }
                )
            }
        }
    }
}

@Composable
private fun rememberDashboardState(context: Context): androidx.compose.runtime.State<DashboardState> {
    return androidx.compose.runtime.produceState(
        initialValue = DashboardState(
            collectionEnabled = false,
            location = "检查中",
            locationPaused = false,
            activityPermission = false,
            notificationPermission = false,
            postNotificationPermission = false,
            music = null,
            musicState = null,
            activity = "UNKNOWN",
            lastUpdate = 0L,
            lastSuccessfulUpload = 0L,
            pending = 0
        ),
        context
    ) {
        while (true) {
            value = withContext(Dispatchers.IO) { readDashboardState(context) }
            delay(1000)
        }
    }
}

private fun readDashboardState(context: Context): DashboardState {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val activity = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
        PackageManager.PERMISSION_GRANTED
    val notification = NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)
    val postNotification = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    return DashboardState(
        collectionEnabled = StatusStore.collectionEnabled(context),
        location = when { fine -> "精确定位" ; coarse -> "大致定位" ; else -> "未授权" },
        locationPaused = StatusStore.isLocationPaused(context),
        activityPermission = activity,
        notificationPermission = notification,
        postNotificationPermission = postNotification,
        music = StatusStore.music(context),
        musicState = StatusStore.musicState(context),
        activity = StatusStore.activity(context),
        lastUpdate = StatusStore.lastUpdate(context),
        lastSuccessfulUpload = StatusStore.lastSuccessfulUpload(context),
        pending = AppDatabase.get(context).eventDao().count()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(
    state: DashboardState,
    serverConnection: ServerConnectionState,
    openSettings: () -> Unit,
    onCheckServer: () -> Unit,
    onStart: () -> Unit,
    onPauseLocation: () -> Unit,
    onStop: () -> Unit
) {
    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = { Text("MaiStatus Android", fontWeight = FontWeight.SemiBold) },
            actions = { IconButton(onClick = openSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            } },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { ServerConnectionCard(serverConnection, onCheckServer) }
            item { CollectionCard(state) }
            item { MusicCard(state) }
            item { LocationCard(state) }
            item { ActivityCard(state) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.collectionEnabled) {
                        Button(onClick = onStart, Modifier.weight(1f)) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp)); Text("开始采集")
                        }
                    } else {
                        Button(onClick = onPauseLocation, Modifier.weight(1f)) {
                            Icon(if (state.locationPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                            Spacer(Modifier.width(8.dp)); Text(if (state.locationPaused) "恢复定位" else "暂停定位")
                        }
                        TextButton(onClick = onStop) {
                            Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp)); Text("停止")
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ServerConnectionCard(
    state: ServerConnectionState,
    onCheck: () -> Unit
) {
    val (title, defaultDetail, icon, color) = when (state.status) {
        ServerConnectionStatus.NOT_CONFIGURED -> ConnectionCopy(
            "未配置", "请在设置中填写 HTTPS 服务器地址", Icons.Default.ErrorOutline,
            MaterialTheme.colorScheme.error
        )
        ServerConnectionStatus.CHECKING -> ConnectionCopy(
            "检查中", "正在请求 ${ServerConnectionViewModel.HEALTH_PATH}，请稍候", Icons.Default.CloudUpload,
            MaterialTheme.colorScheme.primary
        )
        ServerConnectionStatus.CONNECTED -> ConnectionCopy(
            "已连接", "健康检查接口返回正常", Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary
        )
        ServerConnectionStatus.TIMEOUT -> ConnectionCopy(
            "网络超时", "连接或读取健康检查接口超时", Icons.Default.ErrorOutline,
            MaterialTheme.colorScheme.error
        )
        ServerConnectionStatus.CONNECTION_FAILED -> ConnectionCopy(
            "连接失败", "无法建立到服务器的网络连接", Icons.Default.ErrorOutline,
            MaterialTheme.colorScheme.error
        )
        ServerConnectionStatus.HTTP_ERROR -> ConnectionCopy(
            "HTTP 服务异常", "健康检查接口没有返回成功状态", Icons.Default.ErrorOutline,
            MaterialTheme.colorScheme.error
        )
        ServerConnectionStatus.TLS_ERROR -> ConnectionCopy(
            "TLS 证书错误", "服务器证书或 TLS 握手校验失败", Icons.Default.ErrorOutline,
            MaterialTheme.colorScheme.error
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = color)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("服务器连接", style = MaterialTheme.typography.labelLarge)
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(state.detail.ifBlank { defaultDetail }, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onCheck) { Text("重新检查") }
            }
        }
    }
}

private data class ConnectionCopy(
    val title: String,
    val supporting: String,
    val icon: ImageVector,
    val color: androidx.compose.ui.graphics.Color
)

@Composable
private fun CollectionCard(state: DashboardState) {
    val status = when {
        !state.collectionEnabled -> "未启动"
        state.location == "未授权" && !state.notificationPermission -> "权限不足"
        state.locationPaused -> "已暂停定位"
        else -> "运行中"
    }
    val icon = if (status == "运行中") Icons.Default.CheckCircle else Icons.Default.ErrorOutline
    StatusCard(icon, "采集状态", status, when {
        state.lastUpdate > 0 -> "最近采集：${formatTime(state.lastUpdate)}"
        else -> "尚未采集到状态事件"
    }) {
        InfoRow(Icons.Default.CloudUpload, "待上传事件", "${state.pending} 条")
        Text(
            if (state.lastSuccessfulUpload > 0) {
                "最近成功上报：${formatTime(state.lastSuccessfulUpload)}"
            } else {
                "尚未成功上报过数据"
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun MusicCard(state: DashboardState) {
    val label = when (state.musicState) {
        "playing" -> "播放中"
        "paused" -> "已暂停"
        "stopped" -> "已停止"
        else -> "暂无歌曲"
    }
    StatusCard(Icons.Default.MusicNote, "音乐状态", label, state.music ?: "等待已授权播放器的音乐通知") {
        if (state.music != null) Text("最近更新：${formatTime(state.lastUpdate)}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LocationCard(state: DashboardState) {
    StatusCard(Icons.Default.LocationOn, "位置状态", state.location,
        if (state.location == "未授权") "请在设置中授予定位权限" else if (state.locationPaused) "定位采集已暂停" else "首页不显示完整经纬度") {
        Text("精度信息仅在采集到真实位置后上报", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActivityCard(state: DashboardState) {
    val value = if (!state.activityPermission) "未授权" else state.activity.takeIf { it != "UNKNOWN" } ?: "暂无活动数据"
    StatusCard(Icons.Default.DirectionsRun, "活动状态", value,
        if (state.activityPermission) "仅显示真实活动识别结果" else "请在设置中授予活动识别权限")
}

@Composable
private fun StatusCard(icon: ImageVector, title: String, value: String, supporting: String, content: @Composable () -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(supporting, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    state: DashboardState,
    onBack: () -> Unit,
    onLocationPermission: () -> Unit,
    onActivityPermission: () -> Unit,
    onNotificationPermission: () -> Unit,
    onNotificationSettings: () -> Unit,
    onPauseLocation: () -> Unit,
    onSave: (String, String, List<String>) -> Unit,
    onSync: () -> Unit
) {
    var url by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var playersText by rememberSaveable { mutableStateOf("") }
    var initialized by rememberSaveable { mutableStateOf(false) }
    if (!initialized) {
        url = LocalContext.current.let { StatusStore.serverUrl(it) }
        token = LocalContext.current.let { StatusStore.deviceCredential(it) }
        playersText = LocalContext.current.let { StatusStore.allowedMusicPlayers(it).joinToString(", ") }
        initialized = true
    }
    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = { Text("设置") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { SettingsHeader("采集设置") }
            item { SettingRow("音乐通知监听", if (state.notificationPermission) "已开启" else "未开启") {
                if (!state.notificationPermission) TextButton(onClick = onNotificationSettings) { Text("去开启") }
            } }
            item { SettingRow("状态通知权限", if (state.postNotificationPermission) "已授权" else "未授权") {
                if (!state.postNotificationPermission) TextButton(onClick = onNotificationPermission) { Text("授权") }
            } }
            item { SettingRow("位置采集", state.location) {
                TextButton(onClick = if (state.location == "未授权") onLocationPermission else onPauseLocation) {
                    Text(if (state.location == "未授权") "授权" else if (state.locationPaused) "恢复" else "暂停")
                }
            } }
            item { SettingRow("活动识别", if (state.activityPermission) "已授权" else "未授权") {
                if (!state.activityPermission) TextButton(onClick = onActivityPermission) { Text("授权") }
            } }
            item { SettingsHeader("服务器设置") }
            item { EditorRow("服务器 HTTPS 地址", url, false) { url = it } }
            item { EditorRow("设备 Token", token, true) { token = it } }
            item { Text("Token 仅保存在应用私有存储中，不会在日志或首页显示", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            item { SettingsAction("保存配置") { onSave(url.trim(), token, playersText.split(",").map { it.trim() }.filter { it.isNotEmpty() }) } }
            item { SettingsHeader("同步设置") }
            item { SettingRow("定时补报间隔", "15 分钟（WorkManager）") }
            item { SettingRow("当前待上传事件", "${state.pending} 条") }
            item { SettingsAction("立即同步") { onSync() } }
            item { SettingsHeader("音乐来源设置") }
            item { EditorRow("播放器包名（逗号分隔）", playersText, false) { playersText = it } }
            item { Text("只接收已填写包名的媒体通知；包名只能包含字母、数字、下划线和点号", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            item { SettingsHeader("隐私与数据") }
            item { SettingRow("权限概览", "定位：${state.location}；活动：${if (state.activityPermission) "已授权" else "未授权"}") }
            item { ListItem(headlineContent = { Text("数据说明") }, supportingContent = { Text("暂停采集只停止接收新事件；已排队事件仍会按现有规则上传。") }, leadingContent = { Icon(Icons.Default.Info, null) }) }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingRow(title: String, value: String, action: @Composable (() -> Unit)? = null) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(value) }, trailingContent = action)
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun EditorRow(label: String, value: String, password: Boolean, onValueChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        label = { Text(label) }, singleLine = true, visualTransformation = if (password)
            androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}

@Composable
private fun SettingsAction(text: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.padding(horizontal = 8.dp)) { Text(text) }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp)); Text("$label：$value", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatTime(time: Long): String = DateFormat.getDateTimeInstance().format(Date(time))

private fun startCollection(context: Context) {
    StatusStore.setCollectionEnabled(context, true)
    ContextCompat.startForegroundService(context, Intent(context, StatusService::class.java))
}

@Composable
private fun MyStatusTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context)
        else androidx.compose.material3.dynamicLightColorScheme(context)
    } else if (dark) {
        androidx.compose.material3.darkColorScheme()
    } else {
        androidx.compose.material3.lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
