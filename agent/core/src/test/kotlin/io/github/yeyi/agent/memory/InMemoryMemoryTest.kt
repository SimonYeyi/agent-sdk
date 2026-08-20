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
        mem.add(ChatMessage.User(listOf(ContentPart.Text("u1"))))
        mem.add(ChatMessage.Assistant(content = "a1"))
        val h = mem.history()
        assertEquals(2, h.size)
        assertEquals("u1", (h[0] as ChatMessage.User).firstTextOrEmpty())
        assertEquals("a1", (h[1] as ChatMessage.Assistant).content)
    }

    @Test
    fun `history returns a snapshot (not the internal list)`() = runTest {
        val mem = InMemoryMemory()
        mem.add(ChatMessage.User(listOf(ContentPart.Text("u1"))))
        val snap = mem.history()
        mem.add(ChatMessage.User(listOf(ContentPart.Text("u2"))))
        assertEquals(1, snap.size)
        assertEquals(2, mem.history().size)
    }

    @Test
    fun `concurrent adds preserve all messages`() = runTest {
        val mem = InMemoryMemory()
        coroutineScope {
            val jobs = (1..100).map { i ->
                async { mem.add(ChatMessage.User(listOf(ContentPart.Text("u$i")))) }
            }
            jobs.forEach { it.await() }
        }
        assertEquals(100, mem.history().size)
    }

    @Test
    fun `mediaArchive field returns working archive instance`() = runTest {
        val memory = InMemoryMemory()
        // 内部 InMemoryMediaArchive 实例 — 用行为测试验证可工作:
        // store 一个 Data 后 resolve 拿回原 base64
        val original = MediaSource.Data("image/jpeg", "BASE64DATA")
        val local = memory.mediaArchive.store(original)
        assertEquals("image/jpeg", local.mimeType)
        // local.fileId 是 UUID,仅断言非空且能 resolve
        val resolved = memory.mediaArchive.resolve(local)
        assertEquals(original.base64, resolved.base64)
        assertEquals(original.mimeType, resolved.mimeType)
    }

    @Test
    fun `resolve missing fileId throws IllegalStateException`() = runTest {
        val memory = InMemoryMemory()
        val ghost = MediaSource.Local("ghost-id", "image/jpeg")
        val ex = assertFailsWith<IllegalStateException> {
            memory.mediaArchive.resolve(ghost)
        }
        assertTrue(ex.message!!.contains("ghost-id"))
    }

    @Test
    fun `add does not auto-rewrite Data to Local — caller decides`() = runTest {
        // InMemoryMemory 是裸存储层,不做归档决策 — caller 自己 store
        val memory = InMemoryMemory()
        val data = MediaSource.Data("image/jpeg", "BASE64DATA")
        memory.add(ChatMessage.User(listOf(ContentPart.Image(data))))
        val history = memory.history()
        assertEquals(1, history.size)
        val userMsg = history[0] as ChatMessage.User
        val src = (userMsg.parts[0] as ContentPart.Image).source
        assertEquals(data, src)  // 透传, 不改写
    }
}
