package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.text
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun ChatMessage.firstTextOrEmpty(): String = when (this) {
    is ChatMessage.User -> parts.text
    is ChatMessage.Assistant -> content ?: ""
    is ChatMessage.ToolResult -> parts.text
    is ChatMessage.System -> content
}

class ReadOnlyMemoryTest {

    @Test
    fun `history returns delegate messages`() = runTest {
        val delegate = InMemoryMemory()
        delegate.add(ChatMessage.User(listOf(ContentPart.Text("hello"))))
        val readOnly = ReadOnlyMemory(delegate)
        assertEquals(1, readOnly.history().size)
        assertEquals("hello", (readOnly.history()[0] as ChatMessage.User).firstTextOrEmpty())
    }

    @Test
    fun `add throws UnsupportedOperationException`() = runTest {
        val delegate = InMemoryMemory()
        delegate.add(ChatMessage.User(listOf(ContentPart.Text("original"))))
        val readOnly = ReadOnlyMemory(delegate)
        assertFailsWith<UnsupportedOperationException> {
            readOnly.add(ChatMessage.User(listOf(ContentPart.Text("new"))))
        }
    }

    @Test
    fun `add does not affect delegate`() = runTest {
        val delegate = InMemoryMemory()
        delegate.add(ChatMessage.User(listOf(ContentPart.Text("original"))))
        val readOnly = ReadOnlyMemory(delegate)
        try {
            readOnly.add(ChatMessage.User(listOf(ContentPart.Text("new"))))
        } catch (_: UnsupportedOperationException) {
            // expected
        }
        // delegate should still have original message
        assertEquals(1, delegate.history().size)
    }
}
