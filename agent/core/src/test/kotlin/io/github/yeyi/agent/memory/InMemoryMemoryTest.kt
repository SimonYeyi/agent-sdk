package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.llm.text
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun ChatMessage.firstTextOrEmpty(): String = when (this) {
    is ChatMessage.User -> parts.text
    is ChatMessage.Assistant -> content ?: ""
    is ChatMessage.ToolResult -> parts.text
    is ChatMessage.System -> content
}

class InMemoryMemoryTest {

    @Test
    fun `add then history returns inserted messages in order`() = runTest {
        val mem = InMemoryMemory()
        mem.add(MemoryEntry(ChatMessage.User(listOf(ContentPart.Text("u1")))))
        mem.add(MemoryEntry(ChatMessage.Assistant(content = "a1")))
        val h = mem.history()
        assertEquals(2, h.size)
        assertEquals("u1", (h[0].message as ChatMessage.User).firstTextOrEmpty())
        assertEquals("a1", (h[1].message as ChatMessage.Assistant).content)
    }

    @Test
    fun `history returns a snapshot (not the internal list)`() = runTest {
        val mem = InMemoryMemory()
        mem.add(MemoryEntry(ChatMessage.User(listOf(ContentPart.Text("u1")))))
        val snap = mem.history()
        mem.add(MemoryEntry(ChatMessage.User(listOf(ContentPart.Text("u2")))))
        assertEquals(1, snap.size)
        assertEquals(2, mem.history().size)
    }

    @Test
    fun `concurrent adds preserve all messages`() = runTest {
        val mem = InMemoryMemory()
        coroutineScope {
            val jobs = (1..100).map { i ->
                async { mem.add(MemoryEntry(ChatMessage.User(listOf(ContentPart.Text("u$i"))))) }
            }
            jobs.forEach { it.await() }
        }
        assertEquals(100, mem.history().size)
    }

    @Test
    fun `add does not auto-rewrite Data to Local — caller decides`() = runTest {
        // InMemoryMemory 是裸存储层,不做归档决策 — caller 自己 store
        val memory = InMemoryMemory()
        val data = MediaSource.Data("image/jpeg", "BASE64DATA")
        memory.add(MemoryEntry(ChatMessage.User(listOf(ContentPart.Image(data)))))
        val history = memory.history()
        assertEquals(1, history.size)
        val userMsg = history[0].message as ChatMessage.User
        val src = (userMsg.parts[0] as ContentPart.Image).source
        assertEquals(data, src)  // 透传, 不改写
    }
}
