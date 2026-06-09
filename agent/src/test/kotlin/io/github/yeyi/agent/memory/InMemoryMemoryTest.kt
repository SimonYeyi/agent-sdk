package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryMemoryTest {

    @Test
    fun `add then history returns inserted messages in order`() = runTest {
        val mem = InMemoryMemory()
        mem.add(ChatMessage.User("u1"))
        mem.add(ChatMessage.Assistant(content = "a1"))
        val h = mem.history()
        assertEquals(2, h.size)
        assertEquals("u1", (h[0] as ChatMessage.User).content)
        assertEquals("a1", (h[1] as ChatMessage.Assistant).content)
    }

    @Test
    fun `clear empties memory`() = runTest {
        val mem = InMemoryMemory()
        mem.add(ChatMessage.User("u1"))
        mem.clear()
        assertTrue(mem.history().isEmpty())
    }

    @Test
    fun `history returns a snapshot (not the internal list)`() = runTest {
        val mem = InMemoryMemory()
        mem.add(ChatMessage.User("u1"))
        val snap = mem.history()
        mem.add(ChatMessage.User("u2"))
        assertEquals(1, snap.size)
        assertEquals(2, mem.history().size)
    }

    @Test
    fun `concurrent adds preserve all messages`() = runTest {
        val mem = InMemoryMemory()
        coroutineScope {
            val jobs = (1..100).map { i ->
                async { mem.add(ChatMessage.User("u$i")) }
            }
            jobs.forEach { it.await() }
        }
        assertEquals(100, mem.history().size)
    }
}
