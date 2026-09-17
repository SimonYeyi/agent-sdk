package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.EchoTool
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.registryOf
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.modality.DefaultModalityAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 用 CompletableDeferred 控制每次 chat() 返回时机的 LlmProvider。
 * 测试可在 run 执行到 LLM 调用时注入 steer，然后 complete 让 run 继续。
 */
class ControllableLlmProvider(
    private val responses: List<ChatResponse>,
) : LlmProvider {
    override val name = "controllable"
    val recordedRequests: MutableList<ChatRequest> = mutableListOf()
    private val gates = responses.map { CompletableDeferred<ChatResponse>() }
    private var index = 0

    fun complete(response: ChatResponse) {
        gates[index].complete(response)
        index++
    }

    fun completeNext() {
        val i = gates.indexOfFirst { !it.isCompleted }
        if (i >= 0) {
            gates[i].complete(responses[i])
        }
    }

    override suspend fun chat(request: ChatRequest): ChatResponse {
        recordedRequests += request
        val i = recordedRequests.size - 1
        return gates[i].await()
    }

    override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> {
        TODO("Not used in these tests")
    }
}

class SteerableTest {

    @Test
    fun `steer returns false when no run is active`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            persona = Persona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(mem.mediaArchive),
            maxRounds = 20,
            maxIterations = 5
        )
        assertFalse(agent.steer(AgentQuery.text("should not be delivered")))
    }

    @Test
    fun `steer after run completes returns false`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "done"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            persona = Persona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(mem.mediaArchive),
            maxRounds = 20,
            maxIterations = 5
        )
        agent.run(AgentQuery.text("hi")).awaitResult()
        assertFalse(agent.steer(AgentQuery.text("too late")))
    }

    @Test
    fun `checkpoint 1 injects steer at iteration head during tool round`() = runTest(UnconfinedTestDispatcher()) {
        // iter 1: 工具调用 → run 挂在第一次 chat() 的 gate 上
        // steer 注入 → complete gate → iter 1 返回工具调用，工具执行
        // iter 2: 检查点①消费 steer → buildRequest 包含 steer 文本 → complete gate → 返回 final
        val echo = EchoTool()
        val provider = ControllableLlmProvider(
            listOf(
                ChatResponse(
                    ChatMessage.Assistant(
                        content = null,
                        toolCalls = listOf(ToolCall("c1", "echo", JsonNull))
                    ),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "final"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            persona = Persona(""),
            llmProvider = provider,
            toolRegistry = registryOf(echo),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(mem.mediaArchive),
            maxRounds = 20,
            maxIterations = 5
        )

        backgroundScope.launch {
            agent.run(AgentQuery.text("hi")).collect { }
        }

        // 让 run 启动并跑到第一次 chat()（挂在 gate 0 上）
        advanceUntilIdle()

        // 注入 steer
        assertTrue(agent.steer(AgentQuery.text("switch to plan B")))

        // 释放第一次 chat() → 工具执行 → iter 2 检查点①消费 steer → buildRequest → 第二次 chat()
        provider.completeNext()
        advanceUntilIdle()

        // 释放第二次 chat() → Final
        provider.completeNext()
        advanceUntilIdle()

        // 第二轮 request 应包含 steer 指令
        val secondRequest = provider.recordedRequests[1]
        val steerInRequest = secondRequest.messages
            .filterIsInstance<ChatMessage.User>()
            .flatMap { it.parts }
            .filterIsInstance<ContentPart.Text>()
            .any { it.text.contains("switch to plan B") }
        assertTrue(steerInRequest, "steer should be injected before iter 2's LLM request")
    }

    @Test
    fun `checkpoint 2 preempts Final when steer arrives before Final`() = runTest(UnconfinedTestDispatcher()) {
        // iter 1: LLM 返回纯文本（无工具调用）→ 检查点②检查 inbox
        // steer 在 LLM 响应前注入 → 检查点②抢占 Final → iter 2 再跑一轮
        val provider = ControllableLlmProvider(
            listOf(
                ChatResponse(ChatMessage.Assistant(content = "first answer"), finishReason = FinishReason.Stop),
                ChatResponse(ChatMessage.Assistant(content = "steered answer"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            persona = Persona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(mem.mediaArchive),
            maxRounds = 20,
            maxIterations = 5
        )

        backgroundScope.launch {
            agent.run(AgentQuery.text("hi")).collect { }
        }

        // run 跑到第一次 chat()（挂在 gate 0 上）
        advanceUntilIdle()

        // 在 LLM 响应前注入 steer
        assertTrue(agent.steer(AgentQuery.text("change direction")))

        // 释放第一次 chat() → 检查点②发现 steer → 抢占 Final → 注入 → iter 2 buildRequest → 第二次 chat()
        provider.completeNext()
        advanceUntilIdle()

        // 释放第二次 chat() → Final
        provider.completeNext()
        advanceUntilIdle()

        // 第二轮 request 应包含 steer 指令
        val secondRequest = provider.recordedRequests.last()
        val steerInRequest = secondRequest.messages
            .filterIsInstance<ChatMessage.User>()
            .flatMap { it.parts }
            .filterIsInstance<ContentPart.Text>()
            .any { it.text.contains("change direction") }
        assertTrue(steerInRequest, "steer instruction should appear in the second LLM request")

        val finalMsg = mem.history().map { it.message }.filterIsInstance<ChatMessage.Assistant>().last()
        assertEquals("steered answer", finalMsg.content)
    }

    @Test
    fun `concurrent run throws IllegalStateException`() = runTest(UnconfinedTestDispatcher()) {
        val provider = ControllableLlmProvider(
            listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            persona = Persona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(mem.mediaArchive),
            maxRounds = 20,
            maxIterations = 5
        )

        backgroundScope.launch {
            agent.run(AgentQuery.text("first")).collect { }
        }

        // 第一个 run 已启动并挂在 chat() 的 gate 上，inboxRef 已被 CAS 设值

        // 第二个 run 应直接抛 IllegalStateException（逻辑异常，不走 Failed 事件）
        val ex = assertFailsWith<IllegalStateException> {
            agent.run(AgentQuery.text("second")).toList()
        }
        assertTrue(ex.message!!.contains("Concurrent run not supported"))

        // 清理：释放第一个 run
        provider.completeNext()
        advanceUntilIdle()
    }

    @Test
    fun `multiple steers are all injected in FIFO order`() = runTest(UnconfinedTestDispatcher()) {
        val echo = EchoTool()
        val provider = ControllableLlmProvider(
            listOf(
                ChatResponse(
                    ChatMessage.Assistant(
                        content = null,
                        toolCalls = listOf(ToolCall("c1", "echo", JsonNull))
                    ),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "final"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            persona = Persona(""),
            llmProvider = provider,
            toolRegistry = registryOf(echo),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(mem.mediaArchive),
            maxRounds = 20,
            maxIterations = 5
        )

        backgroundScope.launch {
            agent.run(AgentQuery.text("hi")).collect { }
        }

        advanceUntilIdle()

        assertTrue(agent.steer(AgentQuery.text("first steer")))
        assertTrue(agent.steer(AgentQuery.text("second steer")))

        provider.completeNext()
        advanceUntilIdle()
        provider.completeNext()
        advanceUntilIdle()

        val secondRequest = provider.recordedRequests[1]
        val steerTexts = secondRequest.messages
            .filterIsInstance<ChatMessage.User>()
            .flatMap { it.parts }
            .filterIsInstance<ContentPart.Text>()
            .map { it.text }
            .filter { it.contains("steer") }

        assertEquals(2, steerTexts.size, "both steers should be injected")
        assertTrue(steerTexts[0].contains("first steer"), "FIFO: first steer should come before second")
        assertTrue(steerTexts[1].contains("second steer"), "FIFO: second steer should come after first")
    }

    /**
     * 契约2：steer 返回 false 时，调用 run() 不会触发非法状态异常。
     *
     * 惯用法 if (!steer()) { run.collect {} } —— 空闲时 steer 必 false，
     * run() 的 CAS(null→new) 必成功，事件流完整 Initial → Final。
     */
    @Test
    fun `contract2 fallback run when idle executes full stream without IllegalStateException`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "hello"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            persona = Persona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(mem.mediaArchive),
            maxRounds = 20,
            maxIterations = 5
        )

        val events = mutableListOf<AgentEvent>()
        if (!agent.steer(AgentQuery.text("steer while idle"))) {
            agent.run(AgentQuery.text("hi")).collect { events += it }
        }

        assertTrue(events.first() is AgentEvent.Initial, "run must start with Initial")
        assertTrue(events.last() is AgentEvent.Final, "run must end with Final")
        assertTrue(events.none { it is AgentEvent.Failed }, "no Failed on clean run")
        assertEquals(1, provider.recordedRequests.size)
    }

    /**
     * 契约1：steer 返回 true 时指令必被消费，不静默丢弃。
     *
     * run 活跃时 steer 返回 true；指令在检查点被注入 memory，
     * 最终以 User 消息出现在 history 中，而不是困死在 buffer 里作废。
     */
    @Test
    fun `contract1 steer true during run is consumed into memory not dropped`() = runTest(UnconfinedTestDispatcher()) {
        val provider = ControllableLlmProvider(
            listOf(
                ChatResponse(ChatMessage.Assistant(content = "first answer"), finishReason = FinishReason.Stop),
                ChatResponse(ChatMessage.Assistant(content = "final"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            persona = Persona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(mem.mediaArchive),
            maxRounds = 20,
            maxIterations = 5
        )

        backgroundScope.launch {
            agent.run(AgentQuery.text("hi")).collect { }
        }
        advanceUntilIdle()

        // 活跃 run 中注入，必须返回 true
        assertTrue(agent.steer(AgentQuery.text("must be consumed")))

        // iter1 返回纯文本 → 检查点②发现 steer 抢占 Final → iter2 再作答
        provider.completeNext()
        advanceUntilIdle()
        provider.completeNext()
        advanceUntilIdle()

        // 指令已注入 memory，未静默丢弃
        val userTexts = mem.history()
            .map { it.message }
            .filterIsInstance<ChatMessage.User>()
            .flatMap { it.parts }
            .filterIsInstance<ContentPart.Text>()
            .map { it.text }
        assertTrue(
            userTexts.any { it.contains("must be consumed") },
            "steer instruction must be consumed into memory, not dropped"
        )
    }

    /**
     * 契约3：两次 run.collect 的事件流不交错。
     *
     * 旧 run 终态(Final)发完 → steer 才返回 false → 新 run 启动。
     * 验证：run1 完整 Initial→Final，run2 以自身 Initial 开头，各自独立不混流。
     */
    @Test
    fun `contract3 sequential fallback runs keep non-interleaved event streams`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "first"), finishReason = FinishReason.Stop),
                ChatResponse(ChatMessage.Assistant(content = "second"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            persona = Persona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(mem.mediaArchive),
            maxRounds = 20,
            maxIterations = 5
        )

        // run1
        val run1 = mutableListOf<AgentEvent>()
        if (!agent.steer(AgentQuery.text("s1"))) {
            agent.run(AgentQuery.text("one")).collect { run1 += it }
        }

        // run1 已终态，steer 必返回 false
        assertFalse(agent.steer(AgentQuery.text("s2")))

        // run2
        val run2 = mutableListOf<AgentEvent>()
        if (!agent.steer(AgentQuery.text("s3"))) {
            agent.run(AgentQuery.text("two")).collect { run2 += it }
        }

        // 各自完整且独立：run1 以 Final 结束，run2 以自身 Initial 开始
        assertTrue(run1.first() is AgentEvent.Initial, "run1 must start with Initial")
        assertTrue(run1.last() is AgentEvent.Final, "run1 must end with Final")
        assertTrue(run2.first() is AgentEvent.Initial, "run2 must start with its own Initial")
        assertTrue(run2.last() is AgentEvent.Final, "run2 must end with Final")
        assertEquals("one", (run1.first() as AgentEvent.Initial).query.parts.first().let { (it as ContentPart.Text).text })
        assertEquals("first", (run1.last() as AgentEvent.Final).result.message.content)
        assertEquals("two", (run2.first() as AgentEvent.Initial).query.parts.first().let { (it as ContentPart.Text).text })
        assertEquals("second", (run2.last() as AgentEvent.Final).result.message.content)
    }
}
