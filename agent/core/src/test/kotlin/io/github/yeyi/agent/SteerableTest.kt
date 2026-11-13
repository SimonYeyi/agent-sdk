@file:OptIn(ExperimentalCoroutinesApi::class)

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
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.modality.DefaultModalityAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
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

    /** 记忆里是否已有含指定文本的用户消息（steer 指令入卷的事实）。 */
    private suspend fun memoryHasInstruction(mem: InMemoryMemory, text: String): Boolean =
        mem.history().any { entry ->
            val msg = entry.message
            msg is ChatMessage.User &&
                    msg.parts.any { it is ContentPart.Text && it.text.contains(text) }
        }

    /** chat() 被调用时发信号 —— 让测试知道执行体已走到终局前的最后一步。 */
    private class SignallingLlm : LlmProvider {
        override val name: String = "signalling"
        val called = CompletableDeferred<Unit>()

        override suspend fun chat(request: ChatRequest): ChatResponse {
            called.complete(Unit)
            return ChatResponse(
                message = ChatMessage.Assistant(content = "done"),
                finishReason = FinishReason.Stop
            )
        }

        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> {
            TODO("Not used in these tests")
        }
    }

    /**
     * 轮转线程调度器：每次 [dispatch] 换一个线程执行，用于确定性地制造
     * "协程挂起后恢复线程发生变化"。
     */
    private class RotatingDispatcher(threadCount: Int = 3) : CoroutineDispatcher() {
        private val executors = List(threadCount) { i ->
            Executors.newSingleThreadExecutor { r -> Thread(r, "rotating-dispatcher-$i") }
        }
        private val next = AtomicInteger(0)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            executors[next.getAndIncrement() % executors.size].execute(block)
        }

        fun shutdown() {
            executors.forEach { it.shutdownNow() }
        }
    }

    @Test
    fun `steer returns false when no run is active`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
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
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
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
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(echo),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
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
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
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
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        backgroundScope.launch {
            agent.run(AgentQuery.text("first")).collect { }
        }

        // 第一个 run 已启动并挂在 chat() 的 gate 上，inboxRef 已被 CAS 设值

        // 第二个 run 应直接抛 IllegalStateException（逻辑异常，不走 Failed 事件）
        val ex = assertFailsWith<IllegalStateException> {
            agent.run(AgentQuery.text("second")).toList()
        }
        assertTrue(ex.message!!.contains("Concurrent run not supported: another run is active"))

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
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(echo),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
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
     * 回归：run() 调用即建轮并挂上执行体 job，steer() 按 activeTurn 判定活跃。
     *
     * 场景：if (!steer()) { run.collect {} } 首次执行后，在 flow 尚未 collect 前
     * 重复执行同一惯用法。若轮次延到收集时才建，第二次 steer() 仍 false，
     * 会再起一个 run → 双 flow 并发 → 第二个 run() 抛并发异常。
     * 修复：run() 函数体内即占槽并启动执行体，steer() 立即 true。
     */
    @Test
    fun `run call eagerly occupies slot before collection to prevent fallback double-run`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "first"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        // 第一次 run() 返回 flow，但尚未 collect —— 轮次已占槽、执行体已启动
        val flow = agent.run(AgentQuery.text("one"))

        // 此刻重复执行 fallback 惯用法：steer 必须返回 true，不触发第二次 run
        assertTrue(
            agent.steer(AgentQuery.text("two")),
            "run() 调用后槽位必须已被占据，防止并发双 run"
        )

        // 正常收集第一个 run，应完整执行且不抛并发异常
        val events = flow.toList()
        assertTrue(events.first() is AgentEvent.Initial, "run must start with Initial")
        assertTrue(events.last() is AgentEvent.Final, "run must end with Final")
        assertEquals(1, provider.recordedRequests.size, "must not start a second run")
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
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
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
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
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
     * 契约：入卷的事实事件化 —— 指令被消费、写入记忆时，该轮事件流发出 [AgentEvent.Steering]。
     *
     * 事件流是收集方唯一的观察通道：注入的指令若不事件化，收集方无从知道用户
     * 输入进了对话（只能自己另开一份状态，与记忆必然漂移）。事件在**写入记忆之后**
     * 发出 —— 收集方看到它时，记忆里已经有这条指令，渲染时刻与事实一致。
     *
     * 签收（指令进了信箱）由 [Agent.Steerable.steer] 的返回值告知，不走事件流。
     */
    @Test
    fun `指令消费入记忆时该轮事件流发出 Steering 事件`() = runTest(UnconfinedTestDispatcher()) {
        val provider = ControllableLlmProvider(
            listOf(
                ChatResponse(ChatMessage.Assistant(content = "first answer"), finishReason = FinishReason.Stop),
                ChatResponse(ChatMessage.Assistant(content = "final"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        val events = mutableListOf<AgentEvent>()
        backgroundScope.launch {
            agent.run(AgentQuery.text("hi")).collect { events += it }
        }
        advanceUntilIdle() // 轮次挂在第一次 chat() 的 gate 上

        assertTrue(agent.steer(AgentQuery.text("switch to plan B")))
        // 签收 ≠ 入卷：此刻指令还在信箱里，记忆与事件流都还没有它
        assertTrue(
            events.none { it is AgentEvent.Steering },
            "签收时不得发 Steering 事件：${events.map { it::class.simpleName }}"
        )
        assertTrue(!memoryHasInstruction(mem, "switch to plan B"), "签收时指令不该在记忆里")

        provider.completeNext() // → 检查点②抢占 Final → 消费指令 → iter2 再作答
        advanceUntilIdle()
        provider.completeNext() // → Final
        advanceUntilIdle()

        val steering = events.filterIsInstance<AgentEvent.Steering>()
        assertEquals(1, steering.size, "消费一批指令 ⟺ 一个 Steering 事件")
        assertEquals("switch to plan B", steering[0].queries.single().parts.text)
        // 抢占路径补发本轮产出:iter1 的 Final 文本已入记忆但没发 Final 事件,
        // Steering 携带它让前端不丢这段产出。检查点①/短路路径为 null,这里测检查点②。
        assertEquals("first answer", steering[0].preemptedResult?.message?.content)

        // 事件发出时指令已在记忆里：渲染时刻与事实一致
        assertTrue(memoryHasInstruction(mem, "switch to plan B"), "Steering 事件发出时指令应已在记忆里")

        // 顺序：Steering 先于终局事件 —— 消费发生在终局裁决之前
        val steeringIndex = events.indexOfFirst { it is AgentEvent.Steering }
        val finalIndex = events.indexOfFirst { it is AgentEvent.Final }
        assertTrue(steeringIndex in 0 until finalIndex, "Steering 应先于 Final：${events.map { it::class.simpleName }}")
    }

    /**
     * 契约的反面：轮次在消费前终结时，不得有 [AgentEvent.Steering]。
     *
     * 指令随轮作废 —— 既没有事件，记忆里也没有它，两边一致（收集方不会渲染
     * 一条根本没进对话的输入）。
     */
    @Test
    fun `轮次在消费前终结时不发布 Steering 事件`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "done"), finishReason = FinishReason.Stop)
            )
        )
        val mem = InMemoryMemory()
        val agent = ReActAgent(
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        val events = agent.run(AgentQuery.text("hi")).toList()
        assertFalse(agent.steer(AgentQuery.text("too late")))
        assertTrue(
            events.none { it is AgentEvent.Steering },
            "未消费的指令不得发布 Steering 事件：${events.map { it::class.simpleName }}"
        )
    }

    /**
     * 回归：终局窗口内 emit 挂起后若恢复线程变化，不得把 agent 打成 Failed。
     *
     * 旧实现用 ReentrantLock 保护终局窗口：emit 是挂起点，协程恢复后可能换线程，
     * unlock 抛 IllegalMonitorStateException → agent 在已发出 Final 之后又发 Failed，
     * 上层（DAG worker）看到的就是"任务莫名失败"。
     *
     * 构造方式：collector 只在收到 Final 时挂起，使 agent 的 emit 恢复必然落到
     * 另一个线程（RotatingDispatcher 每次 dispatch 换一个线程）。
     */
    @Test
    fun `final emission resumed on another thread does not fail the run`() = runBlocking {
        val dispatcher = RotatingDispatcher()
        try {
            repeat(5) { iteration ->
                val provider = FakeLlmProvider(
                    nonStreamResponses = listOf(
                        ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
                    )
                )
                val agent = ReActAgent(
                    id = "test",
                    persona = AgentPersona(""),
                    llmProvider = provider,
                    toolRegistry = registryOf(),
                    memory = InMemoryMemory(),
                    modalityAdapter = DefaultModalityAdapter(null),
                    maxRounds = 20,
                    maxIterations = 5
                )
                val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
                withContext(dispatcher) {
                    agent.run(AgentQuery.text("hi")).collect { e ->
                        if (e is AgentEvent.Final) delay(10) // 挂起 → agent 的 emit 恢复时换线程
                        events += e
                    }
                }
                assertTrue(
                    events.any { it is AgentEvent.Final },
                    "iteration $iteration must end with Final: ${events.map { it::class.simpleName }}"
                )
                assertTrue(
                    events.none { it is AgentEvent.Failed },
                    "iteration $iteration must not fail: ${events.map { it::class.simpleName }}"
                )
            }
        } finally {
            dispatcher.shutdown()
        }
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
            id = "test",
            persona = AgentPersona(""),
            llmProvider = provider,
            toolRegistry = registryOf(),
            memory = mem,
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5,
            dispatcher = StandardTestDispatcher(testScheduler)
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

    /**
     * 回归：与终局交接同时到达的 steer 必须返回 false —— 那一轮已经死了。
     *
     * 终局路径"先还槽、后发终局事件"：还槽之后槽位已空，这期间到达的 steer()
     * 读到空槽位 → 直接返回 false，调用方按 "if (!steer) run(...)" 兜底开新轮，指令送达。
     * 若顺序反了（先发事件、后还槽），steer() 可能读到还占着槽位的那一轮并投递成功 ——
     * 指令进了没人再排空的信箱，被静默丢弃，且调用方以为已被接受、不再兜底 —— 指令丢失。
     *
     * 构造：收集方在 Initial 上磨蹭，把终局交接拉长；另一个线程在这个窗口里 steer。
     */
    @Test
    fun `steer racing with the final handover reports no active round`() = runBlocking {
        val llm = SignallingLlm()
        val agent = ReActAgent(
            id = "test",
            persona = AgentPersona(""),
            llmProvider = llm,
            toolRegistry = registryOf(),
            memory = InMemoryMemory(),
            modalityAdapter = DefaultModalityAdapter(null),
            maxRounds = 20,
            maxIterations = 5
        )

        val collector = launch(Dispatchers.Default) {
            agent.run(AgentQuery.text("hi")).collect {
                if (it is AgentEvent.Initial) delay(300) // 让 finalize 持闸等 Final
            }
        }
        llm.called.await() // 执行体已调过模型 → 即将进入终局
        delay(50)          // 让它进到 finalize 里、持闸等着

        val steerResult = CompletableDeferred<Boolean>()
        launch(Dispatchers.Default) {
            steerResult.complete(agent.steer(AgentQuery.text("mid")))
        }

        assertFalse(
            steerResult.await(),
            "a steer racing with the final handover must report no active round"
        )
        collector.join()
    }

    /**
     * 契约4：终局事件的回调里可同步开新轮，并把指令 steer 进新轮。
     *
     * 终局路径"先还槽、后发终局事件"，且终局事件在闸外发出 —— 故回调里
     * agent.run() 必定起得来（槽位已还），随后的 steer() 命中新轮并当场返回 true，
     * 既不等闸也不阻塞。
     */
    @Test
    fun `terminal event handler can start the next round and steer into it synchronously`() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = ControllableLlmProvider(
                listOf(
                    ChatResponse(ChatMessage.Assistant(content = "first"), finishReason = FinishReason.Stop),
                    ChatResponse(ChatMessage.Assistant(content = "second"), finishReason = FinishReason.Stop)
                )
            )
            val mem = InMemoryMemory()
            val agent = ReActAgent(
                id = "test",
                persona = AgentPersona(""),
                llmProvider = provider,
                toolRegistry = registryOf(),
                memory = mem,
                modalityAdapter = DefaultModalityAdapter(null),
                maxRounds = 20,
                maxIterations = 5,
                dispatcher = StandardTestDispatcher(testScheduler)
            )

            val firstEvents = mutableListOf<AgentEvent>()
            val secondEvents = mutableListOf<AgentEvent>()
            val steerResult = CompletableDeferred<Boolean>()

            backgroundScope.launch {
                agent.run(AgentQuery.text("one")).collect { event ->
                    firstEvents += event
                    if (event is AgentEvent.Final) {
                        // 终局回调内同步开新轮：槽位已还，run() 必定起得来
                        val next = agent.run(AgentQuery.text("two"))
                        // 同步 steer 进新轮：当场返回 true，不等闸
                        steerResult.complete(agent.steer(AgentQuery.text("into round two")))
                        backgroundScope.launch { next.collect { secondEvents += it } }
                    }
                }
            }
            advanceUntilIdle() // 第一轮跑到 chat() 的 gate 上

            provider.completeNext() // 第一轮拿到响应 → 终局 → 回调内开新轮并 steer
            advanceUntilIdle()

            assertTrue(steerResult.await(), "steer into the new round must be delivered synchronously")

            provider.completeNext() // 第二轮拿到响应
            advanceUntilIdle()

            assertTrue(firstEvents.first() is AgentEvent.Initial)
            assertTrue(firstEvents.last() is AgentEvent.Final)
            assertTrue(secondEvents.first() is AgentEvent.Initial, "round two starts with its own Initial")
            assertTrue(secondEvents.last() is AgentEvent.Final, "round two ends with Final")

            val userTexts = mem.history()
                .map { it.message }
                .filterIsInstance<ChatMessage.User>()
                .flatMap { it.parts }
                .filterIsInstance<ContentPart.Text>()
                .map { it.text }
            assertTrue(
                userTexts.any { it.contains("into round two") },
                "the steered instruction must reach the new round: $userTexts"
            )
        }
}
