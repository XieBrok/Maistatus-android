package com.example.mystatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlsTest {
    @Test fun buildsStandardHttpsUrls() {
        assertEquals("https://status.example.com/", ServerUrls.normalizeBaseUrl("https://status.example.com"))
        assertEquals("https://status.example.com/api/v1/events", ServerUrls.eventsUrl("https://status.example.com"))
        assertEquals("https://status.example.com/healthz", ServerUrls.healthUrl("https://status.example.com"))
        assertEquals("https://status.example.com/api/v1/devices/heartbeat", ServerUrls.heartbeatUrl("https://status.example.com"))
    }

    @Test fun acceptsTrailingSlashAndMigratesHistoricalPaths() {
        assertEquals("https://status.example.com/", ServerUrls.normalizeBaseUrl("https://status.example.com/"))
        assertEquals("https://status.example.com/", ServerUrls.normalizeBaseUrl("https://status.example.com/api/v1/events"))
        assertEquals("https://status.example.com/", ServerUrls.normalizeBaseUrl("https://status.example.com/healthz"))
    }

    @Test fun rejectsUnsafeOrUnexpectedInput() {
        assertNull(ServerUrls.normalizeBaseUrl("http://status.example.com"))
        assertNull(ServerUrls.normalizeBaseUrl("https://status.example.com?token=1"))
        assertNull(ServerUrls.normalizeBaseUrl("https://status.example.com#health"))
        assertNull(ServerUrls.normalizeBaseUrl("https://status.example.com/api/v1/evens/healthz"))
        assertEquals("https://status.example.com/", ServerUrls.normalizeBaseUrl("https://status.example.com/api/v1/evens"))
    }
}
