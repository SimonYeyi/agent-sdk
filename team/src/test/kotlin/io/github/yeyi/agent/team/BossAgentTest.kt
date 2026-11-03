@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.team

import io.github.yeyi.agent.Agent.Streamable
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.memory.InMemoryMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BossAgentTest {

    private fun createBossAgent(
        bb: BulletinBoard = BulletinBoard(),
        provider: LlmProvider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    message = ChatMessage.Assistant(content = "Hello from boss"),
                    finishReason = FinishReason.Stop,
                ),
                ChatResponse(
                    message = ChatMessage.Assistant(content = "continuation"),
                    finishReason = FinishReason.Stop,
                )
            )
        ),
    ): Pair<BossAgent, BulletinBoard> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val innerAgent = agent {
            llmProvider(provider)
            memory(InMemoryMemory(), 20)
            maxIterations(1) // single-turn agent
        }
        val boss = BossAgent(innerAgent as Streamable, scope)
        runBlocking { boss.attach(bb) }
        return boss to bb
    }

    /**
     * Fake LLM provider where the chatStream call at [delayIndex] suspends for [delayMs] ms
     * before streaming the scripted response; other calls return immediately.
     * Used to keep a specific round (e.g. the report round) in-flight long enough
     * to interleave boss.shutdown() with its execution.
     */
    private class SlowCallIndexLlmProvider(
        private val responses: List<ChatResponse>,
        private val delayIndex: Int,
        private val delayMs: Long,
    ) : LlmProvider {
        override val name: String = "slow-call-index"
        private var index = 0

        override suspend fun chat(request: ChatRequest): ChatResponse {
            val i = index++
            check(i < responses.size) {
                "SlowCallIndexLlmProvider: chat() called ${i + 1} times, but only ${responses.size} responses scripted"
            }
            if (i == delayIndex) delay(delayMs)
            return responses[i]
        }

        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow {
            val i = index++
            check(i < responses.size) {
                "SlowCallIndexLlmProvider: chatStream() called ${i + 1} times, but only ${responses.size} responses scripted"
            }
            if (i == delayIndex) delay(delayMs)
            val response = responses[i]
            response.message.content?.takeIf { it.isNotEmpty() }
                ?.let { emit(ChatResponseEvent.ContentDelta(it)) }
            response.message.toolCalls.forEach { call ->
                emit(ChatResponseEvent.ToolCallDelta(call.id, call.name, call.arguments.toString()))
            }
            emit(ChatResponseEvent.Done(response.usage, response.finishReason))
        }
    }

    /**
     * Fake LLM provider where the first [chat] call suspends for [firstCallDelayMs] ms
     * before returning the first scripted response; subsequent calls return the next
     * scripted response immediately. Used by the supersede test to keep the first run's
     * round in RUNNING long enough to interleave further run() calls on the busy path.
     */
    private class SlowFirstLlmProvider(
        private val responses: List<ChatResponse>,
        private val firstCallDelayMs: Long,
    ) : LlmProvider {
        override val name: String = "slow-first"
        private var index = 0

        override suspend fun chat(request: ChatRequest): ChatResponse {
            val i = index++
            check(i < responses.size) {
                "SlowFirstLlmProvider: chat() called ${i + 1} times, but only ${responses.size} responses scripted"
            }
            if (i == 0) delay(firstCallDelayMs)
            return responses[i]
        }

        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow {
            val i = index++
            check(i < responses.size) {
                "SlowFirstLlmProvider: chatStream() called ${i + 1} times, but only ${responses.size} responses scripted"
            }
            // 首次调用在流内挂起：Boss 用户轮以 runStream 驱动，保持"首个 LLM 调用在途"
            // 的语义（第一个 run 占用槽位，后续 run 排队），与 chat() 的 delay 行为对齐。
            if (i == 0) delay(firstCallDelayMs)
            val response = responses[i]
            response.message.content?.takeIf { it.isNotEmpty() }
                ?.let { emit(ChatResponseEvent.ContentDelta(it)) }
            response.message.toolCalls.forEach { call ->
                emit(ChatResponseEvent.ToolCallDelta(call.id, call.name, call.arguments.toString()))
            }
            emit(ChatResponseEvent.Done(response.usage, response.finishReason))
        }
    }

    @Test
    fun `run returns events`() = runTest {
        val (boss, _) = createBossAgent()

        val events = boss.run(AgentQuery.text("hello")).toList()
        assertTrue(events.isNotEmpty())
    }

    @Test
    fun `report is a valid SharedFlow`() {
        val (boss, _) = createBossAgent()
        // Should not throw when subscribing
        val job = GlobalScope.launch { boss.report.collect { } }
        job.cancel()
    }

    @Test
    fun `shutdown cancels scope`() = runBlocking {
        val (boss, _) = createBossAgent()
        // createBossAgent 已同步等到订阅就绪 (attach 用 onSubscription + CompletableDeferred 等自己的 collector 注册),
        // 直接 shutdown 即可, 无需额外 delay.
        boss.shutdown()
        boss.shutdown() // 幂等
        // shutdown 后 run 抛 IllegalStateException
        val e = assertFailsWith<IllegalStateException> { boss.run(AgentQuery.text("hello")) }
        assertTrue("Agent is shut down" in (e.message ?: ""))
    }

    // ===== Queue behavior =====

    @Test
    fun `shutdown terminates queued run flows`() = runBlocking {
        // run1 挂起在首个 LLM 调用时，run2 处于排队（未启动，无执行者关闭其 channel）。
        // shutdown 必须兜底关闭所有 round channel，否则 run2 的收集方永久挂起。
        val (boss, _) = createBossAgent(
            provider = SlowFirstLlmProvider(
                responses = listOf(
                    ChatResponse(message = ChatMessage.Assistant("r1"), finishReason = FinishReason.Stop),
                    ChatResponse(message = ChatMessage.Assistant("r2"), finishReason = FinishReason.Stop),
                ),
                firstCallDelayMs = 500
            )
        )
        val job1 = launch { boss.run(AgentQuery.text("one")).toList() }
        val job2 = launch { boss.run(AgentQuery.text("two")).toList() }
        delay(50) // run1 已被调度并挂起在 LLM, run2 排队
        boss.shutdown()
        withTimeout(1000) {
            job1.join()
            job2.join()
        }
        assertTrue(job1.isCompleted)
        assertTrue(job2.isCompleted)
    }

    /**
     * 回归：shutdown 终止在途 run flow 时，收集方以取消异常收尾 —— 不是静默截断。
     *
     * 这是架构 2 下的真实呈现方式，不是架构 1 的「catch 异常判取消」：BossAgent 的回合流
     * 是 channel 支撑的，shutdown 以 cause 关闭 channel，collect 随之抛出该 cause
     * （close-cause）。消费端据此只做收尾，不靠异常类型推断轮次结果 —— 轮次结果由
     * 终局事件（Final/Failed/Canceled）承载。
     * 静默收尾（channel 无 cause 关闭）会让收集方看到"正常结束但没有终态事件"，
     * 违反 [io.github.yeyi.agent.Agent.Shutdownable] 的统一终止协议。
     */
    @Test
    fun `shutdown terminates in-flight run flow with cancellation`() = runBlocking {
        val (boss, _) = createBossAgent(
            provider = SlowFirstLlmProvider(
                responses = listOf(
                    ChatResponse(message = ChatMessage.Assistant("r1"), finishReason = FinishReason.Stop)
                ),
                firstCallDelayMs = 500
            )
        )

        val caught = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                boss.run(AgentQuery.text("one")).toList()
                caught.complete(null)
            } catch (e: CancellationException) {
                caught.complete(e)
            }
        }
        delay(50) // run 已被调度并挂起在 LLM
        boss.shutdown()
        withTimeout(1000) { job.join() }

        assertTrue(
            caught.await() is CancellationException,
            "shutdown 应以取消异常终止在途 flow —— 实际结果: ${caught.await()}"
        )
        // 收集方协程本身未被取消（异常由 collect 外围捕获）。
        assertFalse(job.isCancelled)
    }

    /**
     * 回归：shutdown 后才被收集的 flow 同样以取消异常收尾 —— 不静默零事件。
     */
    @Test
    fun `run flow collected after shutdown ends with cancellation`() = runBlocking {
        val (boss, _) = createBossAgent()

        val flow = boss.run(AgentQuery.text("hello"))
        boss.shutdown()

        val e = assertFailsWith<CancellationException> { flow.toList() }
        // 关闭原因透传到收集方：调用方据此区分"已关闭"与其它取消。
        assertTrue(e.message.orEmpty().contains("shut down"))
    }

    /**
     * 回归：shutdown 与 run 并发时，新轮不得成为"无人执行、channel 永不关闭"的孤儿 ——
     * 收集方要么收到取消异常，要么 run 直接抛终态异常，绝不永久挂起。
     *
     * 孤儿窗口：run 通过"scope 仍活跃"检查后、登记进登记表之前，shutdown 扫过登记表 ——
     * 之后登记的轮次既没有执行者，也没人关它的 channel。
     */
    @Test
    fun `concurrent run and shutdown never leaves a collector hanging`() = runBlocking {
        repeat(20) {
            val (boss, _) = createBossAgent()
            val collector = launch {
                // run 既可能正常跑完、也可能抛终态异常、也可能被取消 —— 都可接受，
                // 唯一不可接受的是永远不结束。
                runCatching { boss.run(AgentQuery.text("hello")).toList() }
            }
            boss.shutdown()
            withTimeout(2000) { collector.join() }
        }
    }

    @Test
    fun `multiple run() calls are queued and processed in order`() = runBlocking {
        // 多个 run() 调用入队，按顺序处理，都正常完成
        val provider = SlowFirstLlmProvider(
            responses = listOf(
                ChatResponse(message = ChatMessage.Assistant(content = "first"), finishReason = FinishReason.Stop),
                ChatResponse(message = ChatMessage.Assistant(content = "second"), finishReason = FinishReason.Stop),
                ChatResponse(message = ChatMessage.Assistant(content = "third"), finishReason = FinishReason.Stop),
            ),
            firstCallDelayMs = 200,
        )
        val (boss, _) = createBossAgent(provider = provider)

        val flow1Events = mutableListOf<AgentEvent>()
        val flow2Events = mutableListOf<AgentEvent>()
        val flow3Events = mutableListOf<AgentEvent>()

        val flow1Job = launch { boss.run(AgentQuery.text("first")).collect { flow1Events.add(it) } }
        delay(50)  // 确保 flow1 进入队列
        val flow2Job = launch { boss.run(AgentQuery.text("second")).collect { flow2Events.add(it) } }
        delay(50)
        val flow3Job = launch { boss.run(AgentQuery.text("third")).collect { flow3Events.add(it) } }

        withTimeout(5000) {
            flow1Job.join()
            flow2Job.join()
            flow3Job.join()
        }

        // all three flows should complete successfully
        assertTrue(flow1Events.lastOrNull() is AgentEvent.Final, "flow1 should end with Final")
        assertTrue(flow2Events.lastOrNull() is AgentEvent.Final, "flow2 should end with Final")
        assertTrue(flow3Events.lastOrNull() is AgentEvent.Final, "flow3 should end with Final")
    }

    @Test
    fun `continuation flow receives events when terminal TaskUpdate arrives`() = runBlocking {
        // 构造 idle 状态 + 派一个 task + 直接 publish 终态 TaskUpdate;
        // 验证 report 流收到 boss LLM 看到结果后发出的续轮 Final.
        val (boss, bb) = createBossAgent()

        // 先 run 一轮初始化 currentRound，否则 handleTaskAssignments 会因 currentRound 未初始化而失败
        val initJob = launch { boss.run(AgentQuery.text("init")).collect { } }
        withTimeout(5000) { initJob.join() }

        // 直接 publish 一个 taskId 的 TaskAssignment (跳过 PublishTaskTool)
        val taskId = "test-task-1"
        bb.publishEvent(
            TaskAssignments(
                query = "init",
                tasks = listOf(
                    TaskAssignment(
                        taskId = taskId,
                        selection = Selection.Tool("dummy"),
                        task = "do something",
                        context = null,
                        dependsOn = emptyList(),
                    )
                )
            )
        )

        // 用 launch(UNDISPATCHED) 同步挂上 boss.report collector —
        // 与 publishAndAwaitFinal 同一个套路,无需 delay 等待 subscribe 完成.
        val report = mutableListOf<AgentEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.report.collect { report.add(it) }
        }

        bb.progressEvent(
            TaskUpdate(taskId, AgentEvent.Final(AgentResult(ChatMessage.Assistant("done"), 1, emptyList(), null)))
        )

        // 等 boss 跑完续轮
        withTimeout(5000) {
            while (report.isEmpty()) delay(50)
        }
        delay(100) // 确保流程完成
        job.cancel()

        assertTrue(report.isNotEmpty(), "report was empty")
        assertTrue(report.any { it is AgentEvent.Final })
    }

    /**
     * 回归：报告轮在途被 shutdown 时，report 热流必须收到 Canceled 终结事件。
     *
     * 报告轮唯一的取消途径是 shutdown —— 取消不能静默截断：与 run 流一致，
     * 每轮必以终结事件（Final / Failed / Canceled）收尾，订阅方据此区分
     * "被终止"与"正常结束"，而非看到一轮被截断却无终态。
     */
    @Test
    fun `shutdown during report round forwards Canceled to report`() = runBlocking {
        // 第 2 个 chatStream 调用（报告轮）延迟 500ms，保证 shutdown 时报告轮仍在途
        val (boss, bb) = createBossAgent(
            provider = SlowCallIndexLlmProvider(
                responses = listOf(
                    ChatResponse(message = ChatMessage.Assistant(content = "init"), finishReason = FinishReason.Stop),
                    ChatResponse(message = ChatMessage.Assistant(content = "report"), finishReason = FinishReason.Stop),
                ),
                delayIndex = 1,
                delayMs = 500,
            )
        )

        // 先 run 一轮初始化 currentRound，否则 handleTaskAssignments 会因 currentRound 未初始化而失败
        val initJob = launch { boss.run(AgentQuery.text("init")).collect { } }
        withTimeout(5000) { initJob.join() }

        // 直接 publish 一个 taskId 的 TaskAssignment (跳过 PublishTaskTool)
        val taskId = "report-task"
        bb.publishEvent(
            TaskAssignments(
                query = "init",
                tasks = listOf(
                    TaskAssignment(
                        taskId = taskId,
                        selection = Selection.Tool("dummy"),
                        task = "do something",
                        context = null,
                        dependsOn = emptyList(),
                    )
                )
            )
        )

        // 用 launch(UNDISPATCHED) 同步挂上 boss.report collector
        val report = mutableListOf<AgentEvent>()
        val reportJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.report.collect { report.add(it) }
        }

        // 终态 TaskUpdate → 触发报告轮（第 2 个 chatStream 调用，进入延迟窗口）
        bb.progressEvent(
            TaskUpdate(taskId, AgentEvent.Final(AgentResult(ChatMessage.Assistant("done"), 1, emptyList(), null)))
        )
        delay(50) // 等报告轮开跑并挂起在延迟窗口

        boss.shutdown()

        // 报告轮必须以 Canceled 终结 —— 不静默截断
        withTimeout(5000) {
            while (report.none { it is AgentEvent.Canceled }) delay(50)
        }
        reportJob.cancel()

        // 兜底与事件路径互斥：无论竞态赢家是谁，report 只收到一次 Canceled，绝不双发
        assertEquals(1, report.count { it is AgentEvent.Canceled }, "Canceled must be emitted exactly once: $report")
        assertTrue(report.last() is AgentEvent.Canceled, "report must end with Canceled: $report")
    }
}
