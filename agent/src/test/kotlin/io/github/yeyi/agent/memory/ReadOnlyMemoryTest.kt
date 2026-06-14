package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ReadOnlyMemoryTest {

    @Test
    fun `history returns delegate messages`() = runTest {
        val delegate = InMemoryMemory()
        delegate.add(ChatMessage.User("hello"))
        val readOnly = ReadOnlyMemory(delegate)
        assertEquals(1, readOnly.history().size)
        assertEquals("hello", (readOnly.history()[0] as ChatMessage.User).content)
    }

    @Test
    fun `add throws UnsupportedOperationException`() = runTest {
        val delegate = InMemoryMemory()
        delegate.add(ChatMessage.User("original"))
        val readOnly = ReadOnlyMemory(delegate)
        assertFailsWith<UnsupportedOperationException> {
            readOnly.add(ChatMessage.User("new"))
        }
    }

    @Test
    fun `add does not affect delegate`() = runTest {
        val delegate = InMemoryMemory()
        delegate.add(ChatMessage.User("original"))
        val readOnly = ReadOnlyMemory(delegate)
        try {
            readOnly.add(ChatMessage.User("new"))
        } catch (_: UnsupportedOperationException) {
            // expected
        }
        // delegate should still have original message
        assertEquals(1, delegate.history().size)
    }
}