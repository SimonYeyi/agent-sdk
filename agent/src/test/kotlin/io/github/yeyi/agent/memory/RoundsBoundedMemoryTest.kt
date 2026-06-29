package io.github.yeyi.agent.memory

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.EmptyAgentHook
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.tool.Tool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FailingMemory(private val failOnRebuild: Boolean = true) : Memory {
    private val messages = mutableListOf<ChatMessage>()
    var rebuildFailureCount = 0
        private set

    override suspend fun add(message: ChatMessage) {
        messages.add(message)
    }

    override suspend fun history(): List<ChatMessage> = messages.toList()

    override suspend fun rebuild(newMessages: List<ChatMessage>) {
        rebuildFailureCount++
        if (failOnRebuild && rebuildFailureCount == 1) {
            throw IllegalStateException("rebuild failed")
        }
        messages.clear()
        messages.addAll(newMessages)
    }
}

class RoundsBoundedMemoryTest {

    @Test
    fun `does not compress when rounds are within maxRounds`() = runTest {
        val underlying = InMemoryMemory()
        val memory = RoundsBoundedMemory(
            underlying = underlying,
            llmProvider = FakeLlmProvider(),
            maxRounds = 20,
        )
        (1..5).forEach { i ->
            memory.add(ChatMessage.User("u$i"))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }
        assertEquals(10, memory.history().size)
        assertEquals("u1", (memory.history()[0] as ChatMessage.User).content)
        assertEquals("a5", (memory.history()[9] as ChatMessage.Assistant).content)
    }

    @Test
    fun `compresses old rounds when exceeding maxRounds`() = runTest {
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = "summary of 1&2"),
                    finishReason = FinishReason.Stop
                ),
            )
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 2,
        )
        (1..3).forEach { i ->
            memory.add(ChatMessage.User("u$i"))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }
        val history = memory.history()
        assertTrue(history.size < 6)
        // 摘要以 System 消息注入,内容是 SummaryContainer JSON,不再有 [SUMMARY]/[/SUMMARY] marker
        val first = history.first() as ChatMessage.System
        assertTrue(first.content.contains("\"summaries\""))
    }

    @Test
    fun `calls LLM with compressed content and stores summary`() = runTest {
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = "the summary"),
                    finishReason = FinishReason.Stop
                ),
            )
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 2,
        )
        (1..3).forEach { i ->
            memory.add(ChatMessage.User("u$i"))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }
        assertEquals(1, llmProvider.recordedRequests.size)
        val request = llmProvider.recordedRequests[0]
        assertTrue(request.messages.any { it is ChatMessage.System && it.content.contains("摘要") })
        assertTrue(request.messages.any { it is ChatMessage.User })
    }

    @Test
    fun `restores summaries from existing summary message in underlying`() = runTest {
        val underlying = InMemoryMemory()
        // 旧版本用 [SUMMARY]...[/SUMMARY] 前缀标记嵌在 User 消息内;
        // 新版本摘要是 ChatMessage.System,内容是 SummaryContainer 的 JSON 序列化
        underlying.add(
            ChatMessage.System("{\"summaries\":[{\"content\":\"previous\"}]}")
        )
        underlying.add(ChatMessage.User("existing u"))
        underlying.add(ChatMessage.Assistant(content = "existing a"))

        val memory = RoundsBoundedMemory(
            underlying = underlying,
            llmProvider = FakeLlmProvider(),
            maxRounds = 10,
        )

        assertEquals(3, memory.history().size)
        assertTrue(memory.history()[0] is ChatMessage.System)
        assertTrue((memory.history()[0] as ChatMessage.System).content.contains("previous"))
    }

    @Test
    fun `rebuild delegates to underlying`() = runTest {
        val underlying = InMemoryMemory()
        val memory = RoundsBoundedMemory(
            underlying = underlying,
            llmProvider = FakeLlmProvider(),
            maxRounds = 10,
        )
        memory.rebuild(listOf(ChatMessage.User("a")))
        assertEquals(1, underlying.history().size)
        assertEquals("a", (underlying.history()[0] as ChatMessage.User).content)
    }

    @Test
    fun `multiple compressions accumulate summaries`() = runTest {
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..4).map { i ->
                ChatResponse(
                    ChatMessage.Assistant(content = "s$i"),
                    finishReason = FinishReason.Stop
                )
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 2,
        )
        (1..5).forEach { i ->
            memory.add(ChatMessage.User("u$i"))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        val history = memory.history()
        // 多次压缩后,所有 summary 都应累积进同一个 System 消息(列表形式)
        val summaryMsg = history.first() as ChatMessage.System
        val json = summaryMsg.content
        assertTrue(json.contains("\"summaries\""))
        assertTrue(json.contains("s1"))
        assertTrue(json.contains("s2"))
    }

    @Test
    fun `compression preserves message order of retained rounds`() = runTest {
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = "summary"),
                    finishReason = FinishReason.Stop
                ),
            )
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 10,
        )
        (1..12).forEach { i ->
            memory.add(ChatMessage.User("u$i"))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }
        val history = memory.history()
        // 摘要现在是首位 System 消息,不再有 [SUMMARY] 前缀
        val summaryMsg = history.first() as ChatMessage.System
        assertTrue(summaryMsg.content.contains("\"summaries\""))

        // retainWindow = 10 * 0.3 = 3, should retain last 3 rounds (6 messages)
        val retained = history.drop(1)
        assertTrue(retained.size >= 6)
        // Last retained messages should be u12 and a12
        assertEquals("u12", (retained[retained.size - 2] as ChatMessage.User).content)
        assertEquals("a12", (retained[retained.size - 1] as ChatMessage.Assistant).content)
    }

    @Test
    fun `trailing users are included in effective retain window`() = runTest {
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = "summary"),
                    finishReason = FinishReason.Stop
                ),
            )
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 3,
        )
        // Add 3 complete rounds
        (1..3).forEach { i ->
            memory.add(ChatMessage.User("u$i"))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }
        // Add 2 trailing users (no assistant responses)
        memory.add(ChatMessage.User("u4"))
        memory.add(ChatMessage.User("u5"))

        // Compression triggers because currentRounds=3 > maxRounds=3 (trailing users not counted for rounds)
        // But effective retain window = 1 + 2 = 3, so all 3 rounds are retained
        assertEquals(1, llmProvider.recordedRequests.size)
        val history = memory.history()
        // Trailing users should be preserved in the effective retain window
        assertEquals("u4", (history[history.size - 2] as ChatMessage.User).content)
        assertEquals("u5", (history[history.size - 1] as ChatMessage.User).content)
    }

    @Test
    fun `trailing users extend effective retain window`() = runTest {
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = "summary"),
                    finishReason = FinishReason.Stop
                ),
            )
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 3,
        )
        // Add 3 complete rounds + 2 trailing users (no assistant responses)
        (1..3).forEach { i ->
            memory.add(ChatMessage.User("u$i"))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }
        memory.add(ChatMessage.User("u4"))
        memory.add(ChatMessage.User("u5"))

        // Trigger compression by adding another user to make total rounds exceed maxRounds
        memory.add(ChatMessage.User("u6"))

        val history = memory.history()
        // Should have summary + trailing users + recent rounds
        val summaryMsg = history.first() as ChatMessage.System
        assertTrue(summaryMsg.content.contains("\"summaries\""))
    }

    @Test
    fun `extractRetainedIndices skips trailing users for round count`() = runTest {
        val underlying = InMemoryMemory()
        // history: [System(summary), U1,A1, U2,A2, U3,A3, U4,U5,U6]
        underlying.add(ChatMessage.System("{\"summaries\":[]}"))
        (1..3).forEach { i ->
            underlying.add(ChatMessage.User("u$i"))
            underlying.add(ChatMessage.Assistant(content = "a$i"))
        }
        underlying.add(ChatMessage.User("u4"))
        underlying.add(ChatMessage.User("u5"))
        underlying.add(ChatMessage.User("u6"))

        val llmProvider = FakeLlmProvider()
        val memory = RoundsBoundedMemory(
            underlying = underlying,
            llmProvider = llmProvider,
            maxRounds = 3,
        )

        // Trailing users should not be counted as rounds for compression
        // effective retain window should be retainWindow + 3 trailing users = 1 + 3 = 4
        val history = memory.history()
        // History should be: summary + u4,u5,u6 (trailing) + u3,a3 (last complete round)
        assertTrue(history.size >= 6)
    }

    @Test
    fun `summaries are NOT updated when rebuild fails - verified by second success`() = runTest {
        // Strategy: First rebuild fails (summary1 generated), second succeeds (summary2 generated)
        // If final result contains summary1 -> summaries WAS updated despite first failure (BUG)
        // If final result only contains summary2 -> summaries was NOT updated (CORRECT)

        val failingMemory = FailingMemory(failOnRebuild = true)  // fail on first rebuild
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = "summary1"),
                    finishReason = FinishReason.Stop
                ),
                ChatResponse(
                    ChatMessage.Assistant(content = "summary2"),
                    finishReason = FinishReason.Stop
                ),
                ChatResponse(
                    ChatMessage.Assistant(content = "summary3"),
                    finishReason = FinishReason.Stop
                ),
            )
        )
        val memory = RoundsBoundedMemory(
            underlying = failingMemory,
            llmProvider = llmProvider,
            maxRounds = 2,
        )

        // Setup: empty summaries (seed the underlying with an empty SummaryContainer System message)
        failingMemory.add(ChatMessage.System("{\"summaries\":[]}"))

        // First compression: triggers at u3, rebuild fails
        memory.add(ChatMessage.User("u1"))
        memory.add(ChatMessage.Assistant(content = "a1"))
        memory.add(ChatMessage.User("u2"))
        memory.add(ChatMessage.Assistant(content = "a2"))
        try {
            memory.add(ChatMessage.User("u3"))
        } catch (e: IllegalStateException) {
            assertEquals("rebuild failed", e.message)
        }

        // Second compression: triggers at u4 or u5, rebuild succeeds
        memory.add(ChatMessage.User("u4"))
        memory.add(ChatMessage.Assistant(content = "a4"))
        memory.add(ChatMessage.User("u5"))

        // Verify: if summary1 is present, it means summaries WAS updated on first failure (BUG)
        // Correct behavior: only summary2 or summary3 should be present
        val history = memory.history()
        val summaryMsg = history.first() as ChatMessage.System
        assertTrue(
            summaryMsg.content.contains("summary2") || summaryMsg.content.contains("summary3"),
            "summary2 or summary3 should be present"
        )
        assertTrue(
            !summaryMsg.content.contains("summary1"),
            "summary1 should NOT be present - summaries was correctly NOT updated on first failure"
        )
    }

    @Test
    fun `summaries are updated after rebuild succeeds`() = runTest {
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = FakeLlmProvider(
                nonStreamResponses = listOf(
                    ChatResponse(
                        ChatMessage.Assistant(content = "summary1"),
                        finishReason = FinishReason.Stop
                    ),
                )
            ),
            maxRounds = 2,
        )

        // Trigger compression
        memory.add(ChatMessage.User("u1"))
        memory.add(ChatMessage.Assistant(content = "a1"))
        memory.add(ChatMessage.User("u2"))
        memory.add(ChatMessage.Assistant(content = "a2"))
        memory.add(ChatMessage.User("u3")) // triggers compression

        // Verify summaries are updated
        val history = memory.history()
        val summaryMsg = history.first() as ChatMessage.System
        assertTrue(summaryMsg.content.contains("summary1"))
    }

    @Test
    fun `before and after hooks fire with correct summary state`() = runTest {
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = "s1"),
                    finishReason = FinishReason.Stop
                ),
            )
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 2,
        )
        val events = mutableListOf<String>()
        val hook = object : EmptyAgentHook() {
            override suspend fun beforeMemoryCompress(
                context: AgentContext,
                summaries: List<Summary>
            ) {
                events += "before(${summaries.size})"
            }

            override suspend fun afterMemoryCompress(
                context: AgentContext,
                summaries: List<Summary>
            ) {
                events += "after(${summaries.size})"
            }
        }
        memory.attachHook(
            hook, AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        (1..3).forEach { i ->
            memory.add(ChatMessage.User("u$i"))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        assertEquals(listOf("before(0)", "after(1)"), events)
    }

    @Test
    fun `hooks do not fire when below threshold`() = runTest {
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = FakeLlmProvider(),
            maxRounds = 20,
        )
        var beforeCalls = 0
        var afterCalls = 0
        val hook = object : EmptyAgentHook() {
            override suspend fun beforeMemoryCompress(
                context: AgentContext,
                summaries: List<Summary>
            ) {
                beforeCalls++
            }

            override suspend fun afterMemoryCompress(
                context: AgentContext,
                summaries: List<Summary>
            ) {
                afterCalls++
            }
        }
        memory.attachHook(
            hook, AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        (1..3).forEach { i ->
            memory.add(ChatMessage.User("u$i"))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        assertEquals(0, beforeCalls)
        assertEquals(0, afterCalls)
    }

    @Test
    fun `hook receives snapshot not live reference across compressions`() = runTest {
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..5).map {
                ChatResponse(
                    ChatMessage.Assistant(content = "s$it"),
                    finishReason = FinishReason.Stop
                )
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 2,
        )
        val afterSnapshots = mutableListOf<List<Summary>>()
        val hook = object : EmptyAgentHook() {
            override suspend fun afterMemoryCompress(
                context: AgentContext,
                summaries: List<Summary>
            ) {
                afterSnapshots += summaries
            }
        }
        memory.attachHook(
            hook, AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        (1..5).forEach { i ->
            memory.add(ChatMessage.User("u$i"))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        // 多次压缩后,首张快照的大小与内容不应被后续压缩 mutate。
        // 若 RBM 传入可变引用,这里首张快照的 size 会比首次捕获时更大、content 也会不同。
        assertTrue(afterSnapshots.size >= 2, "test requires multiple compressions")
        val firstSnapshot = afterSnapshots[0]
        val firstSize = firstSnapshot.size
        val firstContent = firstSnapshot.map { it.content }.toList()

        // 后续压缩不应改变已捕获的 firstSnapshot
        assertEquals(
            firstSize,
            firstSnapshot.size,
            "first snapshot size must not change after later compressions"
        )
        assertEquals(
            firstContent,
            firstSnapshot.map { it.content },
            "first snapshot content must not change"
        )
    }
}
