package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentExtensionsTest {

    private val imagePart = ContentPart.Image(MediaSource.Http("https://example.com/x.png"))
    private val audioPart = ContentPart.Audio(MediaSource.Http("https://example.com/x.wav"))

    private fun tr(toolCallId: String, toolName: String, vararg parts: ContentPart) =
        ChatMessage.ToolResult(toolCallId = toolCallId, toolName = toolName, parts = parts.toList())

    @Test
    fun `ToolResult with only text produces a single ToolResult with no follow-up`() {
        val messages = tr("c1", "echo", ContentPart.Text("hello")).adaptModality()
        assertEquals(1, messages.size)
        val result = messages[0] as ChatMessage.ToolResult
        assertEquals("c1", result.toolCallId)
        assertEquals(listOf<ContentPart>(ContentPart.Text("hello")), result.parts)
        assertEquals(false, result.isError)
    }

    @Test
    fun `ToolResult with text and media splits into text-only ToolResult and User with prefix`() {
        val messages = tr("c1", "echo", ContentPart.Text("result:"), imagePart, ContentPart.Text("more"))
            .adaptModality()
        assertEquals(2, messages.size)

        val result = messages[0] as ChatMessage.ToolResult
        assertEquals(listOf<ContentPart>(ContentPart.Text("result:"), ContentPart.Text("more")), result.parts)

        val user = messages[1] as ChatMessage.User
        assertEquals(2, user.parts.size)
        assertEquals(ContentPart.Text("[from echo]"), user.parts[0])
        assertEquals(imagePart, user.parts[1])
    }

    @Test
    fun `ToolResult with only media produces empty ToolResult and User with prefix`() {
        val messages = tr("c1", "echo", imagePart, audioPart).adaptModality()
        assertEquals(2, messages.size)

        val result = messages[0] as ChatMessage.ToolResult
        assertEquals(emptyList<ContentPart>(), result.parts)

        val user = messages[1] as ChatMessage.User
        assertEquals(listOf(ContentPart.Text("[from echo]"), imagePart, audioPart), user.parts)
    }

    @Test
    fun `isError propagates to split ToolResult`() {
        val messages = tr("c1", "echo", ContentPart.Text("boom")).copy(isError = true).adaptModality()
        val result = messages[0] as ChatMessage.ToolResult
        assertEquals(true, result.isError)
    }

    @Test
    fun `toolName propagates to User prefix`() {
        val messages = tr("c99", "echo", ContentPart.Text("ok"), imagePart).adaptModality()
        val user = messages[1] as ChatMessage.User
        val prefix = user.parts[0] as ContentPart.Text
        assertTrue(prefix.text.contains("echo"), "User prefix should mention toolName")
    }
}