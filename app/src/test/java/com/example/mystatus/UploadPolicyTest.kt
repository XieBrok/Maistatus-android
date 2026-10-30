package com.example.mystatus

import org.junit.Assert.assertEquals
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

    @Test
    fun successfulResponsesDeliverTheEvent() {
        assertEquals(UploadOutcome.DELIVERED, UploadPolicy.classify(200))
        assertEquals(UploadOutcome.DELIVERED, UploadPolicy.classify(204))
    }

    @Test
    fun transientFailuresAreRetried() {
        assertEquals(UploadOutcome.RETRY, UploadPolicy.classify(408))
        assertEquals(UploadOutcome.RETRY, UploadPolicy.classify(429))
        assertEquals(UploadOutcome.RETRY, UploadPolicy.classify(500))
        assertEquals(UploadOutcome.RETRY, UploadPolicy.classify(503))
    }

    @Test
    fun authAndRoutingFailuresKeepTheQueue() {
        assertEquals(UploadOutcome.HALT, UploadPolicy.classify(401))
        assertEquals(UploadOutcome.HALT, UploadPolicy.classify(403))
        assertEquals(UploadOutcome.HALT, UploadPolicy.classify(404))
        assertEquals(UploadOutcome.HALT, UploadPolicy.classify(405))
    }

    @Test
    fun malformedEventsAreDroppedIndividually() {
        assertEquals(UploadOutcome.DROP, UploadPolicy.classify(400))
        assertEquals(UploadOutcome.DROP, UploadPolicy.classify(409))
        assertEquals(UploadOutcome.DROP, UploadPolicy.classify(422))
    }
}
