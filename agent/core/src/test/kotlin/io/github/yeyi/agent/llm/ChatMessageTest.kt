package io.github.yeyi.agent.llm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatMessageTest {
    private val json = Json

    @Test
    fun `System message has Role System`() {
        val msg = ChatMessage.System("hi")
        assertEquals(Role.System, msg.role)
        assertEquals("hi", msg.content)
    }

    @Test
    fun `User constructs with parts list`() {
        val msg = ChatMessage.User(listOf(ContentPart.Text("hi")))
        assertEquals(Role.User, msg.role)
    }

    @Test
    fun `User rejects empty parts`() {
        assertFailsWith<IllegalArgumentException> {
            ChatMessage.User(emptyList())
        }
    }

    @Test
    fun `User round-trips through JSON`() {
        val msg = ChatMessage.User(listOf(
            ContentPart.Text("look"),
            ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
        ))
        val s = json.encodeToString<ChatMessage>(msg)
        val back = json.decodeFromString<ChatMessage>(s)
        assertEquals(msg, back)
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
        val msg = ChatMessage.ToolResult(toolCallId = "c1", toolName = "echo", parts = listOf(ContentPart.Text("ok")))
        assertEquals(Role.Tool, msg.role)
        assertEquals(false, msg.isError)
        assertEquals("ok", msg.parts.text)
    }

    @Test
    fun `ToolResult can mark isError true`() {
        val msg = ChatMessage.ToolResult(toolCallId = "c1", toolName = "echo", parts = listOf(ContentPart.Text("boom")), isError = true)
        assertTrue(msg.isError)
    }
}
