package io.github.yeyi.agent.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProtocolFrameTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ProtocolFrame serializes payload directly without wrapper`() {
        val payload = buildJsonObject {
            put("type", "session.create")
            put("event_id", "event_1")
        }
        val frame = ProtocolFrame(payload)

        val serialized = json.encodeToString(JsonObject.serializer(), frame.payload)

        assertFalse(serialized.startsWith("""{"payload":"""))
        assertEquals("""{"type":"session.create","event_id":"event_1"}""", serialized)
    }

    @Test
    fun `ProtocolFrame deserializes from server response`() {
        val jsonStr = """{"type": "session.created", "session": {"id": "sess_123"}}"""

        val payload = json.decodeFromString(JsonObject.serializer(), jsonStr)
        val frame = ProtocolFrame(payload)

        assertEquals("session.created", (frame.payload["type"] as JsonPrimitive).content)
        assertEquals("sess_123", ((frame.payload["session"] as? JsonObject)?.get("id") as? JsonPrimitive)?.content)
    }
}
