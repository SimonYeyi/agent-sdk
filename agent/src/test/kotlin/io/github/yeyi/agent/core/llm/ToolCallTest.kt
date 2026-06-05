package io.github.yeyi.agent.llm

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolCallTest {
    @Test
    fun `ToolCall holds id name and arguments`() {
        val args = JsonObject(mapOf("x" to JsonPrimitive(1)))
        val call = ToolCall(id = "call_1", name = "echo", arguments = args)
        assertEquals("call_1", call.id)
        assertEquals("echo", call.name)
        assertEquals(args, call.arguments)
    }

    @Test
    fun `ToolCall equals compares all fields`() {
        val a = ToolCall("c1", "echo", JsonNull)
        val b = ToolCall("c1", "echo", JsonNull)
        assertEquals(a, b)
    }
}
