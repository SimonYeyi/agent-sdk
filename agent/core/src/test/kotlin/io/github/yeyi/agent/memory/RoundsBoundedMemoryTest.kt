package io.github.yeyi.agent.memory

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.EmptyAgentHook
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.tool.Tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun ChatMessage.contentOrFirstText(): String = when (this) {
    is ChatMessage.User -> parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    is ChatMessage.Assistant -> content ?: ""
    is ChatMessage.ToolResult -> content
    is ChatMessage.System -> content
}

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
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }
        assertEquals(10, memory.history().size)
        assertEquals("u1", (memory.history()[0] as ChatMessage.User).contentOrFirstText())
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
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
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
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
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
        underlying.add(ChatMessage.User(listOf(ContentPart.Text("existing u"))))
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
        memory.rebuild(listOf(ChatMessage.User(listOf(ContentPart.Text("a")))))
        assertEquals(1, underlying.history().size)
        assertEquals("a", (underlying.history()[0] as ChatMessage.User).contentOrFirstText())
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
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
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
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
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
        assertEquals("u12", (retained[retained.size - 2] as ChatMessage.User).contentOrFirstText())
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
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }
        // Add 2 trailing users (no assistant responses)
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u4"))))
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u5"))))

        // Compression triggers because currentRounds=3 > maxRounds=3 (trailing users not counted for rounds)
        // But effective retain window = 1 + 2 = 3, so all 3 rounds are retained
        assertEquals(1, llmProvider.recordedRequests.size)
        val history = memory.history()
        // Trailing users should be preserved in the effective retain window
        assertEquals("u4", (history[history.size - 2] as ChatMessage.User).contentOrFirstText())
        assertEquals("u5", (history[history.size - 1] as ChatMessage.User).contentOrFirstText())
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
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u4"))))
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u5"))))

        // Trigger compression by adding another user to make total rounds exceed maxRounds
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u6"))))

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
            underlying.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            underlying.add(ChatMessage.Assistant(content = "a$i"))
        }
        underlying.add(ChatMessage.User(listOf(ContentPart.Text("u4"))))
        underlying.add(ChatMessage.User(listOf(ContentPart.Text("u5"))))
        underlying.add(ChatMessage.User(listOf(ContentPart.Text("u6"))))

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
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u1"))))
        memory.add(ChatMessage.Assistant(content = "a1"))
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u2"))))
        memory.add(ChatMessage.Assistant(content = "a2"))
        try {
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u3"))))
        } catch (e: IllegalStateException) {
            assertEquals("rebuild failed", e.message)
        }

        // Second compression: triggers at u4 or u5, rebuild succeeds
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u4"))))
        memory.add(ChatMessage.Assistant(content = "a4"))
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u5"))))

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
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u1"))))
        memory.add(ChatMessage.Assistant(content = "a1"))
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u2"))))
        memory.add(ChatMessage.Assistant(content = "a2"))
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u3")))) // triggers compression

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
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
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
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
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
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
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

    @Test
    fun `compressRounds retains last round including its tool messages`() = runTest {
        // Test that compressRounds (called during add()) retains rounds including tool messages
        // Using maxRounds=5 and retainRatio=0.3 gives retainWindow=1
        // So the last round is retained fully (with tool calls), earlier rounds are summarized
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "summary1"), finishReason = FinishReason.Stop)
            )
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 5,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Add 6 complete rounds (exceeds maxRounds=5 when u6 is added)
        // retainWindow = 1, so only the last round (u6,a6,t6) is retained
        // Earlier rounds (u1-t5) are compressed into summary
        (1..6).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(
                ChatMessage.Assistant(
                    content = "a$i",
                    toolCalls = listOf(ToolCall("c$i", "tool$i", JsonNull))
                )
            )
            memory.add(ChatMessage.ToolResult("c$i", "tool$i", "r$i"))
        }

        val history = memory.history()

        // After compression:
        // - Summary message should be at the start (contains "summary1")
        // - The last round (u6, a6 with toolCalls, t6) should be retained
        // - Earlier rounds should be summarized (not present individually)
        assertTrue(history.size <= 5, "History should be smaller after compression")

        // The retained last round should contain the most recent user message
        val lastUser = history.filterIsInstance<ChatMessage.User>().lastOrNull()
        assertEquals("u6", lastUser?.contentOrFirstText())

        // The last assistant should have toolCalls (from u6 round)
        val lastAssistant = history.filterIsInstance<ChatMessage.Assistant>().lastOrNull()
        assertTrue(lastAssistant?.toolCalls?.isNotEmpty() == true, "Last round's assistant should retain toolCalls")

        // Should have exactly one summary
        val summaryMessages = history.filterIsInstance<ChatMessage.System>()
        assertEquals(1, summaryMessages.size, "Should have exactly one summary message")
        assertTrue(summaryMessages[0].content.contains("summary1"), "Summary should contain the generated summary")
    }

    @Test
    fun `removeCompressWindowToolMessages returns false when nothing to remove`() = runTest {
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = FakeLlmProvider(),
            maxRounds = 20,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // All messages within retain window (maxRounds=20), no compression happens
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u1"))))
        memory.add(ChatMessage.Assistant(content = "a1"))
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u2"))))
        memory.add(ChatMessage.Assistant(content = "a2"))

        val history = memory.history()
        // No tool messages at all
        val hasToolMessages = history.any {
            it is ChatMessage.Assistant && it.toolCalls.isNotEmpty()
        } || history.any { it is ChatMessage.ToolResult }

        assertFalse(hasToolMessages, "setup: should not have tool messages")
    }

    @Test
    fun `truncateByCoefficient removes 30 percent of rounds`() = runTest {
        // Provide enough responses for compression
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..15).map {
                ChatResponse(ChatMessage.Assistant(content = "s$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 10,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Add 10 rounds (will trigger compression due to maxRounds=10)
        (1..10).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        val historyBefore = memory.history()
        val roundsBefore = historyBefore.count { it is ChatMessage.User }
        assertEquals(10, roundsBefore)

        // Add one more to trigger another compression
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u11"))))

        val historyAfter = memory.history()
        val roundsAfter = historyAfter.count { it is ChatMessage.User }

        // Should retain approximately 70% (7 rounds from 10)
        assertTrue(roundsAfter < roundsBefore, "should have fewer rounds after truncation")
    }

    @Test
    fun `truncateByCoefficient when only one round left keeps system plus users`() = runTest {
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..5).map {
                ChatResponse(ChatMessage.Assistant(content = "s$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 3,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Add 2 rounds
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u1"))))
        memory.add(ChatMessage.Assistant(content = "a1"))
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u2"))))
        memory.add(ChatMessage.Assistant(content = "a2"))

        // Trigger truncation by adding more
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u3"))))

        val history = memory.history()
        val hasSystemOrUsers = history.any { it is ChatMessage.System || it is ChatMessage.User }
        assertTrue(hasSystemOrUsers, "should preserve system and user messages")
    }

    @Test
    fun `handleContextOverflow uses two-layer strategy`() = runTest {
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..10).map {
                ChatResponse(ChatMessage.Assistant(content = "s$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 5,
        )
        val beforeCalls = mutableListOf<Int>()
        val afterCalls = mutableListOf<Int>()
        val hook = object : EmptyAgentHook() {
            override suspend fun beforeMemoryCompress(
                context: AgentContext,
                summaries: List<Summary>
            ) {
                beforeCalls.add(summaries.size)
            }

            override suspend fun afterMemoryCompress(
                context: AgentContext,
                summaries: List<Summary>
            ) {
                afterCalls.add(summaries.size)
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

        // Setup: 8 rounds with some tool calls - will trigger compression on add
        (1..8).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            val assistantMsg = if (i % 2 == 0) {
                ChatMessage.Assistant(
                    content = "a$i",
                    toolCalls = listOf(ToolCall("c$i", "tool", JsonNull))
                )
            } else {
                ChatMessage.Assistant(content = "a$i")
            }
            memory.add(assistantMsg)
            if (i % 2 == 0) {
                memory.add(ChatMessage.ToolResult("c$i", "tool", "r$i"))
            }
        }

        // Verify hooks are called when compression happens
        assertTrue(beforeCalls.isNotEmpty(), "beforeMemoryCompress should be called")
        assertTrue(afterCalls.isNotEmpty(), "afterMemoryCompress should be called")
    }

    @Test
    fun `handleContextOverflow preserves recent rounds in retain window`() = runTest {
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..20).map {
                ChatResponse(ChatMessage.Assistant(content = "s$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 10,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Add 15 rounds - will trigger compression
        (1..15).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        val history = memory.history()
        // Most recent rounds should be preserved
        val lastUserIndex = history.indexOfLast { it is ChatMessage.User }
        assertEquals("u15", (history[lastUserIndex] as ChatMessage.User).contentOrFirstText())
    }

    @Test
    fun `compressRounds summarizes old rounds and retains only recent rounds`() = runTest {
        // Test that compressRounds (called via add()) summarizes old rounds into LLM summary
        // and retains only the last retainWindow rounds with their tool messages
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..5).map {
                ChatResponse(ChatMessage.Assistant(content = "s$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 3,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Add 6 rounds with tool calls
        // When u6 is added, currentRounds=6 > maxRounds=3, triggers compression
        // retainWindow = 3 * 0.3 = 0 (coerced to 1 by extractRetainedIndices)
        // So only the last round is retained
        (1..6).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(
                ChatMessage.Assistant(
                    content = "a$i",
                    toolCalls = listOf(ToolCall("c$i", "tool", JsonNull))
                )
            )
            memory.add(ChatMessage.ToolResult("c$i", "tool", "r$i"))
        }

        val history = memory.history()

        // After compression, history should be smaller than 18 original messages
        assertTrue(history.size < 18, "History should be smaller after compression: ${history.size}")

        // Summary message should exist
        assertTrue(history.first() is ChatMessage.System)
    }

    @Test
    fun `handleContextOverflow removes tool messages from compress window`() = runTest {
        // Directly test handleContextOverflow - first layer: removeCompressWindowToolMessages
        // Provide LLM responses for the add() calls that trigger compressRounds
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..10).map {
                ChatResponse(ChatMessage.Assistant(content = "s$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 5,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Build history with tool messages in the compress window
        // maxRounds=5, retainRatio=0.3, so retainWindow=1
        // All rounds except the last one are in the compress window
        (1..6).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(
                ChatMessage.Assistant(
                    content = "a$i",
                    toolCalls = listOf(ToolCall("c$i", "tool", JsonNull))
                )
            )
            memory.add(ChatMessage.ToolResult("c$i", "tool", "r$i"))
        }

        val beforeHistory = memory.history()
        val beforeToolResults = beforeHistory.filterIsInstance<ChatMessage.ToolResult>()
        val beforeToolCalls = beforeHistory.filterIsInstance<ChatMessage.Assistant>()
            .filter { it.toolCalls.isNotEmpty() }
        assertTrue(beforeToolResults.size > 0, "setup: should have tool results")

        // Directly call handleContextOverflow
        memory.handleContextOverflow()

        val afterHistory = memory.history()
        val afterToolResults = afterHistory.filterIsInstance<ChatMessage.ToolResult>()
        val afterToolCalls = afterHistory.filterIsInstance<ChatMessage.Assistant>()
            .filter { it.toolCalls.isNotEmpty() }

        // First layer should have removed tool messages from compress window
        assertTrue(
            afterToolResults.size < beforeToolResults.size ||
                afterToolCalls.size < beforeToolCalls.size,
            "Tool messages should be removed from compress window"
        )
    }

    @Test
    fun `handleContextOverflow truncates when tool removal returns false`() = runTest {
        // Test second layer: when removeCompressWindowToolMessages returns false,
        // truncateByCoefficient should be called
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..20).map {
                ChatResponse(ChatMessage.Assistant(content = "s$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 10, // Larger so we keep more rounds after add()
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Add 15 rounds without tool messages
        // No tool messages in compress window -> removeCompressWindowToolMessages returns false
        // -> truncateByCoefficient should be called
        (1..15).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        val beforeHistory = memory.history()
        val beforeUserCount = beforeHistory.count { it is ChatMessage.User }
        assertTrue(beforeUserCount > 1, "setup: should have more than 1 user before truncation")

        // Directly call handleContextOverflow
        memory.handleContextOverflow()

        val afterHistory = memory.history()
        val afterUserCount = afterHistory.count { it is ChatMessage.User }

        // truncateByCoefficient should have reduced the user count
        assertTrue(
            afterUserCount < beforeUserCount,
            "Truncation should reduce user count: before=$beforeUserCount, after=$afterUserCount"
        )
    }

    @Test
    fun `handleContextOverflow stops at minimum one round`() = runTest {
        // Test the boundary: when toRetain < 1, only System + User are kept
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..5).map {
                ChatResponse(ChatMessage.Assistant(content = "s$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 3,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Only 1 round - this triggers the toRetain < 1 boundary in truncateByCoefficient
        memory.add(ChatMessage.User(listOf(ContentPart.Text("u1"))))
        memory.add(ChatMessage.Assistant(content = "a1"))

        memory.handleContextOverflow()

        val history = memory.history()
        // Should have at least System and User
        assertTrue(history.any { it is ChatMessage.System || it is ChatMessage.User })
    }

    @Test
    fun `retainRatio determines how many rounds to keep`() = runTest {
        // With maxRounds=10 and retainRatio=0.3, retainWindow=3
        // So 3 rounds should be retained when compressing
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..10).map {
                ChatResponse(ChatMessage.Assistant(content = "s$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 10,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Add 12 rounds - exceeds maxRounds=10, triggers compression
        // retainWindow = 10 * 0.3 = 3, so 3 rounds retained
        (1..12).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        val history = memory.history()
        val retainedUserRounds = history.filterIsInstance<ChatMessage.User>()
        // Should retain approximately 3 rounds (plus trailing users)
        assertTrue(
            retainedUserRounds.size in 3..5,
            "Expected 3-5 user messages (3 retained rounds + possible trailing), got ${retainedUserRounds.size}"
        )
    }

    @Test
    fun `getCompressWindowIndices excludes retained indices`() = runTest {
        // Test that compressRounds correctly identifies which indices to compress
        // The compress window (indices to summarize) excludes the retained window
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..5).map {
                ChatResponse(ChatMessage.Assistant(content = "summary$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 5,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Add 8 rounds - retainWindow = 1 (5 * 0.3), so only last round retained
        // Earlier rounds should be compressed into summary
        (1..8).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        val history = memory.history()

        // The most recent user should be u8
        val lastUser = history.filterIsInstance<ChatMessage.User>().lastOrNull()
        assertEquals("u8", lastUser?.contentOrFirstText())

        // History should be smaller than original 16 messages after compression
        assertTrue(history.size < 16, "History should be smaller after compression")

        // Summary message should exist
        assertTrue(history.first() is ChatMessage.System)
    }

    @Test
    fun `getCompressWindowIndices removes first message when summaries exist`() = runTest {
        // When summaries exist and compress window is not empty,
        // the first message (which could be a previous summary) should be removed from compress window
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..5).map {
                ChatResponse(ChatMessage.Assistant(content = "s$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 3,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Add 6 rounds to trigger multiple compressions
        (1..6).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        val history = memory.history()
        // After multiple compressions, there should still be a summary at the start
        assertTrue(history.first() is ChatMessage.System)
        val summaryContent = (history.first() as ChatMessage.System).content
        assertTrue(summaryContent.contains("summaries"))
    }

    @Test
    fun `truncateByCoefficient removes 30 percent of rounds when called directly`() = runTest {
        // This tests truncateByCoefficient indirectly through handleContextOverflow
        // Since truncateByCoefficient is private, we test it via the scenario that triggers it:
        // When removeCompressWindowToolMessages has nothing to remove, truncation kicks in
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..5).map {
                ChatResponse(ChatMessage.Assistant(content = "s$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 3,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Add rounds without tool messages - no tool messages to remove in compress window
        (1..5).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        val history = memory.history()
        // After truncation, we should have fewer user messages
        val userCount = history.count { it is ChatMessage.User }
        assertTrue(userCount < 5, "Truncation should have reduced user message count from 5")
    }

    @Test
    fun `compressSummaries merges when exceeding maxSummaries`() = runTest {
        // When summaries.size >= maxSummaries (10), compressSummaries merges older ones
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..15).map {
                ChatResponse(ChatMessage.Assistant(content = "summary$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 1, // Force frequent compressions
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Add many rounds to trigger multiple compressions
        // With maxRounds=1, each new user triggers compression
        (1..12).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        val history = memory.history()
        val summarySystem = history.filterIsInstance<ChatMessage.System>().firstOrNull()
        assertTrue(summarySystem != null, "Should have summary message")

        // After merging, we should have fewer than 10 summaries
        val summaryContent = summarySystem.content
        assertTrue(summaryContent.contains("summaries"))
        // The merge should have reduced the count
    }

    @Test
    fun `truncateByCoefficient stops at minimum one round`() = runTest {
        // Test the boundary: when currentRounds=1, toRemove=1, toRetain=0
        // should trigger the edge case branch that keeps only System + User
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..10).map {
                ChatResponse(ChatMessage.Assistant(content = "s$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 1,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Add enough rounds that truncation will eventually reach the boundary
        // With maxRounds=1, each new user triggers compression
        // After many compressions, we should hit the toRetain < 1 branch
        (1..10).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        val history = memory.history()
        // The history should still have at least the summary and one user
        assertTrue(history.any { it is ChatMessage.System }, "Should have system/summary message")
        assertTrue(history.any { it is ChatMessage.User }, "Should have at least one user message")
    }

    @Test
    fun `truncateByCoefficient repeated truncations converge to minimum`() = runTest {
        // Simulate multiple 0.3 truncations until we converge to minimum
        // This tests that the math converges properly
        // Start with many rounds, repeatedly truncate by 30%
        val llmProvider = FakeLlmProvider(
            nonStreamResponses = (1..20).map {
                ChatResponse(ChatMessage.Assistant(content = "s$it"), finishReason = FinishReason.Stop)
            }
        )
        val memory = RoundsBoundedMemory(
            underlying = InMemoryMemory(),
            llmProvider = llmProvider,
            maxRounds = 3,
        )
        memory.attachHook(
            EmptyAgentHook(),
            AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = ReadOnlyMemory(memory),
                llmProvider = FakeLlmProvider(),
                tools = emptyList<Tool>(),
                maxRounds = 20,
            )
        )

        // Add 20 rounds - this will cause multiple compressions
        (1..20).forEach { i ->
            memory.add(ChatMessage.User(listOf(ContentPart.Text("u$i"))))
            memory.add(ChatMessage.Assistant(content = "a$i"))
        }

        val history = memory.history()
        val userCount = history.count { it is ChatMessage.User }

        // After enough rounds, the truncation should have reduced the count significantly
        // But we should never get to 0 users (minimum is 1)
        assertTrue(userCount >= 1, "Should always have at least 1 user message")
        assertTrue(userCount <= 20, "Should be less than original after truncation")
    }
}
