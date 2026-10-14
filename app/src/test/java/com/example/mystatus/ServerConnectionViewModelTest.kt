package com.example.mystatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerConnectionViewModelTest {
    @Test
    fun healthCheckUsesOriginForEndpointWithPath() {
        assertEquals(
            "https://example.test/healthz",
            healthCheckUrl("https://example.test/api/v1/events")
        )
    }

    @Test
    fun healthCheckRemovesTrailingSlashQueryAndFragment() {
        assertEquals(
            "https://example.test/healthz",
            healthCheckUrl("https://example.test/api/v1/events/?device=1#status")
        )
    }

    @Test
    fun healthCheckWorksForHostOnlyEndpoint() {
        assertEquals("https://example.test/healthz", healthCheckUrl("https://example.test"))
    }

    @Test
    fun healthCheckRejectsInvalidEndpoint() {
        assertNull(healthCheckUrl("not-a-url"))
    }
}
