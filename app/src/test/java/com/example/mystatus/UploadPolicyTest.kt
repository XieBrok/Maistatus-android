package com.example.mystatus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadPolicyTest {
    @Test
    fun onlyHttpsEndpointsAreAllowed() {
        assertTrue(UploadPolicy.isAllowedEndpoint("https://example.test/status"))
        assertFalse(UploadPolicy.isAllowedEndpoint("http://example.test/status"))
        assertFalse(UploadPolicy.isAllowedEndpoint("not-a-url"))
    }
}
