package com.example.mystatus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

enum class ServerConnectionStatus {
    NOT_CONFIGURED,
    CHECKING,
    CONNECTED,
    TIMEOUT,
    CONNECTION_FAILED,
    HTTP_ERROR,
    TLS_ERROR
}

data class ServerConnectionState(
    val status: ServerConnectionStatus = ServerConnectionStatus.NOT_CONFIGURED,
    val detail: String = "",
    val checkedAt: Long = 0L
)

class ServerConnectionViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ServerConnectionState())
    val state: StateFlow<ServerConnectionState> = _state.asStateFlow()
    private var checkJob: Job? = null

    fun checkConnection() {
        checkJob?.cancel()
        val endpoint = StatusStore.serverUrl(getApplication())
        if (endpoint.isBlank()) {
            _state.value = ServerConnectionState(
                status = ServerConnectionStatus.NOT_CONFIGURED,
                detail = "请在设置中填写 HTTPS 服务器地址"
            )
            return
        }

        _state.value = ServerConnectionState(
            status = ServerConnectionStatus.CHECKING,
            detail = "正在请求健康检查接口"
        )
        checkJob = viewModelScope.launch(Dispatchers.IO) {
            _state.value = check(endpoint)
        }
    }

    private fun check(endpoint: String): ServerConnectionState {
        val checkedAt = System.currentTimeMillis()
        val healthUrl = endpoint.trimEnd('/') + "/health"
        val url = try {
            URL(healthUrl)
        } catch (_: MalformedURLException) {
            return ServerConnectionState(
                ServerConnectionStatus.CONNECTION_FAILED,
                "服务器地址格式不正确",
                checkedAt
            )
        }
        if (url.protocol != "https") {
            return ServerConnectionState(
                ServerConnectionStatus.CONNECTION_FAILED,
                "服务器地址必须使用 HTTPS",
                checkedAt
            )
        }

        val connection = try {
            url.openConnection() as? HttpsURLConnection
                ?: return ServerConnectionState(
                    ServerConnectionStatus.CONNECTION_FAILED,
                    "无法创建 HTTPS 连接",
                    checkedAt
                )
        } catch (_: IOException) {
            return ServerConnectionState(
                ServerConnectionStatus.CONNECTION_FAILED,
                "无法创建网络连接",
                checkedAt
            )
        }

        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            when {
                code in 200..299 -> ServerConnectionState(
                    ServerConnectionStatus.CONNECTED,
                    "健康检查接口返回正常",
                    checkedAt
                )
                code == HttpURLConnection.HTTP_NOT_FOUND -> ServerConnectionState(
                    ServerConnectionStatus.HTTP_ERROR,
                    "服务端尚未实现 /health 健康检查接口",
                    checkedAt
                )
                else -> ServerConnectionState(
                    ServerConnectionStatus.HTTP_ERROR,
                    "健康检查接口返回 HTTP $code",
                    checkedAt
                )
            }
        } catch (_: SocketTimeoutException) {
            ServerConnectionState(ServerConnectionStatus.TIMEOUT, "连接或读取超时", checkedAt)
        } catch (_: SSLHandshakeException) {
            ServerConnectionState(ServerConnectionStatus.TLS_ERROR, "TLS 证书或握手校验失败", checkedAt)
        } catch (_: SSLPeerUnverifiedException) {
            ServerConnectionState(ServerConnectionStatus.TLS_ERROR, "TLS 证书未通过校验", checkedAt)
        } catch (_: CertificateException) {
            ServerConnectionState(ServerConnectionStatus.TLS_ERROR, "TLS 证书校验失败", checkedAt)
        } catch (_: SSLException) {
            ServerConnectionState(ServerConnectionStatus.TLS_ERROR, "TLS 安全连接失败", checkedAt)
        } catch (_: ConnectException) {
            ServerConnectionState(ServerConnectionStatus.CONNECTION_FAILED, "无法连接服务器", checkedAt)
        } catch (_: IOException) {
            ServerConnectionState(ServerConnectionStatus.CONNECTION_FAILED, "网络连接失败", checkedAt)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
    }
}
