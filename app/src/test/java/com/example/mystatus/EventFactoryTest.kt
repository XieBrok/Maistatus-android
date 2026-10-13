package com.example.mystatus

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EventFactoryTest {
    @Test
    fun eventsHaveStableIdentityTimeTypeAndPayload() {
        val first = EventFactory.create("location", JSONObject().put("accuracy_meters", 4.5), 123L)
        val second = EventFactory.create("location", JSONObject().put("accuracy_meters", 4.5), 123L)
        assertNotEquals(first.eventId, second.eventId)
        assertEquals(123L, first.occurredAt)
        assertEquals("location", first.type)
        assertEquals(4.5, JSONObject(first.payload).getDouble("accuracy_meters"), 0.0)
    }
}
