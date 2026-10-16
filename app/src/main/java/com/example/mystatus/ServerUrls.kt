package com.example.mystatus

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrls {
    const val EVENTS_PATH = "/api/v1/events"
    const val LEGACY_EVENTS_PATH = "/api/v1/evens"
    const val HEARTBEAT_PATH = "/api/v1/devices/heartbeat"
    const val HEALTH_PATH = "/healthz"

    fun normalizeBaseUrl(input: String): String? {
        val url = input.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null || url.port != 443
        ) return null
        val path = url.encodedPath.trimEnd('/')
        if (path.isNotEmpty() && path != EVENTS_PATH && path != LEGACY_EVENTS_PATH && path != HEALTH_PATH) return null
        return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    }

    fun eventsUrl(baseUrl: String): String? = buildEndpoint(baseUrl, EVENTS_PATH)

    fun healthUrl(baseUrl: String): String? = buildEndpoint(baseUrl, HEALTH_PATH)

    fun heartbeatUrl(baseUrl: String): String? = buildEndpoint(baseUrl, HEARTBEAT_PATH)

    private fun buildEndpoint(baseUrl: String, path: String): String? =
        normalizeBaseUrl(baseUrl)?.toHttpUrlOrNull()?.newBuilder()
            ?.encodedPath(path)?.build()?.toString()
}
