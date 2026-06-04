package io.github.yeyi.agent.core.llm

import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatMessageTest {
    @Test
    fun `System message has Role System`() {
        val msg = ChatMessage.System("hi")
        assertEquals(Role.System, msg.role)
        assertEquals("hi", msg.content)
    }

    @Test
    fun `User message has Role User`() {
        val msg = ChatMessage.User("hi")
        assertEquals(Role.User, msg.role)
    }

    @Test
    fun `Assistant message has Role Assistant and optional fields`() {
        val msg = ChatMessage.Assistant()
        assertEquals(Role.Assistant, msg.role)
        assertEquals(null, msg.content)
        assertTrue(msg.toolCalls.isEmpty())
    }

    @Test
    fun `Assistant message can have content and toolCalls`() {
        val call = ToolCall(id = "c1", name = "echo", arguments = JsonNull)
        val msg = ChatMessage.Assistant(content = "ok", toolCalls = listOf(call))
        assertEquals("ok", msg.content)
        assertEquals(1, msg.toolCalls.size)
    }

    @Test
    fun `ToolResult message has Role Tool and required fields`() {
        val msg = ChatMessage.ToolResult(toolCallId = "c1", toolName = "echo", content = "ok")
        assertEquals(Role.Tool, msg.role)
        assertEquals(false, msg.isError)
    }

    @Test
    fun `ToolResult can mark isError true`() {
        val msg = ChatMessage.ToolResult(toolCallId = "c1", toolName = "echo", content = "boom", isError = true)
        assertTrue(msg.isError)
    }
}
