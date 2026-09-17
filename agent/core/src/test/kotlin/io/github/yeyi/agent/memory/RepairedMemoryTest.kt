package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun toolCall(id: String, name: String = "noop"): ToolCall =
    ToolCall(id, name, buildJsonObject {})

private fun userMsg(text: String): ChatMessage.User =
    ChatMessage.User(listOf(ContentPart.Text(text)))

private fun assistantMsg(vararg calls: ToolCall): ChatMessage.Assistant =
    ChatMessage.Assistant(toolCalls = calls.toList())

private fun toolResult(id: String, name: String = "noop", text: String = "ok"): ChatMessage.ToolResult =
    ChatMessage.ToolResult(id, name, listOf(ContentPart.Text(text)))

class RepairedMemoryTest {

    // ── repairOrphans 扩展函数的纯逻辑用例 ──────────────────────────────────

    @Test
    fun `repairOrphans — no assistant — nothing to do`() = runTest {
        val mem = InMemoryMemory()
        mem.add(MemoryEntry(userMsg("u1")))

        val sizeBefore = mem.history().size
        mem.repairOrphans(RepairReason.CRASHED)

        assertEquals(sizeBefore, mem.history().size)
    }

    @Test
    fun `repairOrphans — assistant without toolCalls — nothing to do`() = runTest {
        val mem = InMemoryMemory()
        mem.add(MemoryEntry(userMsg("u1")))
        mem.add(MemoryEntry(ChatMessage.Assistant(content = "plain")))

        val sizeBefore = mem.history().size
        mem.repairOrphans(RepairReason.CRASHED)

        assertEquals(sizeBefore, mem.history().size)
    }

    @Test
    fun `repairOrphans — all toolCalls already paired — nothing to do`() = runTest {
        val mem = InMemoryMemory()
        mem.add(MemoryEntry(userMsg("u1")))
        mem.add(MemoryEntry(assistantMsg(toolCall("c1"), toolCall("c2"))))
        mem.add(MemoryEntry(toolResult("c1")))
        mem.add(MemoryEntry(toolResult("c2")))

        val sizeBefore = mem.history().size
        mem.repairOrphans(RepairReason.CRASHED)

        assertEquals(sizeBefore, mem.history().size)
    }

    @Test
    fun `repairOrphans — single orphan with crashed reason — appends crashed marker`() = runTest {
        val mem = InMemoryMemory()
        mem.add(MemoryEntry(userMsg("u1")))
        mem.add(MemoryEntry(assistantMsg(toolCall("c1", "search"))))

        mem.repairOrphans(RepairReason.CRASHED)

        val h = mem.history()
        assertEquals(3, h.size)
        val tr = h[2].message as ChatMessage.ToolResult
        assertEquals("c1", tr.toolCallId)
        assertEquals("search", tr.toolName)
        assertEquals(true, tr.isError)
        assertEquals(
            RepairReason.CRASHED,
            tr.parts.filterIsInstance<ContentPart.Text>().single().text
        )
    }

    @Test
    fun `repairOrphans — single orphan with cancelled reason — appends cancelled marker`() = runTest {
        val mem = InMemoryMemory()
        mem.add(MemoryEntry(userMsg("u1")))
        mem.add(MemoryEntry(assistantMsg(toolCall("c1"))))

        mem.repairOrphans(RepairReason.CANCELLED)

        val tr = mem.history().single { it.message is ChatMessage.ToolResult }.message as ChatMessage.ToolResult
        assertEquals(RepairReason.CANCELLED, tr.parts.filterIsInstance<ContentPart.Text>().single().text)
        assertTrue(tr.isError)
    }

    @Test
    fun `repairOrphans — multiple orphans partially paired — only the unanswered ones get closed`() = runTest {
        val mem = InMemoryMemory()
        mem.add(MemoryEntry(userMsg("u1")))
        mem.add(MemoryEntry(assistantMsg(toolCall("c1"), toolCall("c2"), toolCall("c3"))))
        mem.add(MemoryEntry(toolResult("c2")))

        mem.repairOrphans(RepairReason.CRASHED)

        val results = mem.history().map { it.message }.filterIsInstance<ChatMessage.ToolResult>()
        assertEquals(3, results.size)
        val normal = results.single { !it.isError }
        assertEquals("c2", normal.toolCallId)
        val closed = results.filter { it.isError }.map { it.toolCallId }.toSet()
        assertEquals(setOf("c1", "c3"), closed)
    }

    @Test
    fun `repairOrphans — idempotent — second call appends nothing`() = runTest {
        val mem = InMemoryMemory()
        mem.add(MemoryEntry(userMsg("u1")))
        mem.add(MemoryEntry(assistantMsg(toolCall("c1"))))

        mem.repairOrphans(RepairReason.CRASHED)
        val afterFirst = mem.history().size

        mem.repairOrphans(RepairReason.CRASHED)
        assertEquals(afterFirst, mem.history().size)
    }

    @Test
    fun `repairOrphans — only the tail assistant is checked — earlier round's orphan left alone`() = runTest {
        val mem = InMemoryMemory()
        // round 1: 故意留孤儿
        mem.add(MemoryEntry(userMsg("u1")))
        mem.add(MemoryEntry(assistantMsg(toolCall("c1"))))
        // round 2: 完整轮
        mem.add(MemoryEntry(userMsg("u2")))
        mem.add(MemoryEntry(ChatMessage.Assistant(content = "done")))

        val sizeBefore = mem.history().size
        mem.repairOrphans(RepairReason.CRASHED)

        // 末条 Assistant 无 toolCalls,不应插入任何东西;c1 的孤儿保持原样
        assertEquals(sizeBefore, mem.history().size)
    }

    @Test
    fun `repairOrphans — tail message is not assistant — still repairs using last assistant`() = runTest {
        // 注意:正常 run 路径不会在 Assistant 和 ToolResult 之间插非 ToolResult 消息,
        // 这里是验证 indexOfLast Assistant 的逻辑正确。
        val mem = InMemoryMemory()
        mem.add(MemoryEntry(userMsg("u1")))
        mem.add(MemoryEntry(assistantMsg(toolCall("c1"))))
        mem.add(MemoryEntry(ChatMessage.System("some system note")))

        mem.repairOrphans(RepairReason.CRASHED)

        val h = mem.history()
        assertEquals(4, h.size) // User + Assistant + System + ToolResult (append 到尾部)
        val tr = h[3].message as ChatMessage.ToolResult
        assertEquals("c1", tr.toolCallId)
        assertEquals(true, tr.isError)
    }

    // ── RepairedMemory 装饰器行为用例 ───────────────────────────────────────

    @Test
    fun `RepairedMemory — first add triggers repair on orphan history`() = runTest {
        val raw = InMemoryMemory()
        raw.add(MemoryEntry(userMsg("u1")))
        raw.add(MemoryEntry(assistantMsg(toolCall("c1"))))
        // c1 没有 ToolResult,模拟崩溃残留

        val repaired = RepairedMemory(raw)
        repaired.add(MemoryEntry(userMsg("u2")))

        // add(u2) 触发了 repair, c1 应该被补了 crashed marker
        val results = raw.history().map { it.message }.filterIsInstance<ChatMessage.ToolResult>()
        assertEquals(1, results.size)
        assertEquals("c1", results[0].toolCallId)
        assertEquals(RepairReason.CRASHED, results[0].parts.filterIsInstance<ContentPart.Text>().single().text)

        // 顺序: u1, assistant(c1), ToolResult(c1, crashed), u2
        val h = raw.history()
        assertEquals(4, h.size)
        assertEquals("c1", (h[2].message as ChatMessage.ToolResult).toolCallId)
        assertEquals("u2", ((h[3].message as ChatMessage.User).parts[0] as ContentPart.Text).text)
    }

    @Test
    fun `RepairedMemory — first history() read triggers repair on orphan history`() = runTest {
        val raw = InMemoryMemory()
        raw.add(MemoryEntry(userMsg("u1")))
        raw.add(MemoryEntry(assistantMsg(toolCall("c1"))))

        val repaired = RepairedMemory(raw)
        // 只读 history,不 add,也应该触发修复
        val h = repaired.history()

        assertEquals(3, h.size)
        val tr = h[2].message as ChatMessage.ToolResult
        assertEquals("c1", tr.toolCallId)
        assertEquals(RepairReason.CRASHED, tr.parts.filterIsInstance<ContentPart.Text>().single().text)
    }

    @Test
    fun `RepairedMemory — only repairs once across multiple adds`() = runTest {
        val raw = InMemoryMemory()
        raw.add(MemoryEntry(userMsg("u1")))
        raw.add(MemoryEntry(assistantMsg(toolCall("c1"))))

        val repaired = RepairedMemory(raw)
        repaired.add(MemoryEntry(userMsg("u2")))
        val afterFirstAdd = raw.history().size

        // 第二次 add 不应该再修出第二条 crashed marker
        repaired.add(MemoryEntry(userMsg("u3")))
        assertEquals(afterFirstAdd + 1, raw.history().size) // 只多了 u3

        val crashMarkers = raw.history().map { it.message }
            .filterIsInstance<ChatMessage.ToolResult>()
            .count { it.parts.any { p -> p is ContentPart.Text && p.text == RepairReason.CRASHED } }
        assertEquals(1, crashMarkers, "repair should run exactly once")
    }

    @Test
    fun `RepairedMemory — clean history adds nothing`() = runTest {
        val raw = InMemoryMemory()
        raw.add(MemoryEntry(userMsg("u1")))
        raw.add(MemoryEntry(ChatMessage.Assistant(content = "done")))

        val sizeBefore = raw.history().size
        val repaired = RepairedMemory(raw)
        repaired.add(MemoryEntry(userMsg("u2")))

        assertEquals(sizeBefore + 1, raw.history().size) // 只多了 u2,没有多余的 ToolResult
        assertFalse(
            raw.history().map { it.message }.any { it is ChatMessage.ToolResult },
            "clean history should not produce any tool result markers"
        )
    }

    @Test
    fun `RepairedMemory — history() after add still returns repaired result`() = runTest {
        val raw = InMemoryMemory()
        raw.add(MemoryEntry(userMsg("u1")))
        raw.add(MemoryEntry(assistantMsg(toolCall("c1"))))

        val repaired = RepairedMemory(raw)
        repaired.add(MemoryEntry(userMsg("u2")))

        // history() 读到的应该是修复后的完整历史
        val h = repaired.history()
        assertEquals(4, h.size)
        // ToolResult(c1, crashed) 应该在 User(u2) 之前
        val c1Idx = h.map { it.message }.indexOfFirst { it is ChatMessage.ToolResult && it.toolCallId == "c1" }
        val u2Idx = h.map { it.message }.indexOfFirst {
            it is ChatMessage.User && it.parts.any { p -> p is ContentPart.Text && p.text == "u2" }
        }
        assertTrue(c1Idx in 0 until u2Idx, "crashed marker must appear before new User message")
    }
}
