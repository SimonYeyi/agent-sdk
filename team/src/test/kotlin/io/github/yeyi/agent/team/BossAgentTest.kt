@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.memory.InMemoryMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
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
        val boss = BossAgent(innerAgent, scope)
        runBlocking { boss.attach(bb) }
        return boss to bb
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

        override fun chatStream(request: ChatRequest): Flow<StreamEvent> =
            kotlinx.coroutines.flow.flow { /* not used by innerAgent.run() */ }
    }

    @Test
    fun `run returns events and state transitions to WAITING`() = runTest {
        val (boss, _) = createBossAgent()

        val events = boss.run("hello").toList()
        assertEquals(BossState.WAITING, boss.state.value)
        assertTrue(events.isNotEmpty())
    }

    @Test
    fun `continuations is a valid SharedFlow`() {
        val (boss, _) = createBossAgent()
        // Should not throw when subscribing
        val job = GlobalScope.launch { boss.continuations.collect { } }
        job.cancel()
    }

    @Test
    fun `state flow reflects WAITING initial state`() {
        val (boss, _) = createBossAgent()
        assertEquals(BossState.WAITING, boss.state.value)
    }

    @Test
    fun `inputting transitions between WAITING and INPUTTING`() {
        val (boss, _) = createBossAgent()
        assertEquals(BossState.WAITING, boss.state.value)

        boss.inputting(true)
        assertEquals(BossState.INPUTTING, boss.state.value)

        boss.inputting(false)
        assertEquals(BossState.WAITING, boss.state.value)
    }

    @Test
    fun `isActive does not interrupt RUNNING`() = runTest {
        val (boss, _) = createBossAgent()
        assertEquals(BossState.WAITING, boss.state.value)

        boss.run("hello")
        boss.inputting(true) // should be no-op
        // state is WAITING after round finishes
    }

    @Test
    fun `shutdown cancels scope`() = runBlocking {
        val (boss, _) = createBossAgent()
        // createBossAgent 已同步等到订阅就绪 (attach blocks on subscriptionCount),
        // 直接 shutdown 即可, 无需额外 delay.
        boss.shutdown()
        // subsequent run returns Failed event
        val events = boss.run("hello").toList()
        assertEquals(1, events.size)
        assertTrue(events[0] is AgentEvent.Failed)
    }

    // ===== Race-free behaviors (spec § 7.5 + § 6.1-6.3) =====

    @Test
    fun `run() supersedes pending round with Failed(CancellationException) when boss busy`() = runBlocking {
        // Spec § 7.5 invariant #2: a newer run() arriving while another round is still RUNNING
        // supersedes the previously-pending round and emits AgentEvent.Failed(CancellationException).
        //
        // Setup uses a slow-first LLM provider so the first round's LLM call suspends long
        // enough for two more run() calls to land while the boss is in state RUNNING:
        //   T1: run("first")  → state WAITING → RUNNING, round1 LLM suspends ~500ms
        //   T2: run("second") → state RUNNING → pendingUserRound = round2 (no previous pending, no supersede)
        //   T3: run("third")  → state RUNNING → supersedeRound(round2) + pendingUserRound = round3
        //   T4: round1 LLM completes → handlePending(postRound=true) picks up round3
        //   T5: runPendingRound(round3) completes → state WAITING
        //
        // Expected:
        //   - flow1 ends with Final("response 1") (round1 was running, never superseded)
        //   - flow2 ends with Failed(CancellationException("superseded by newer run()")) — round2 was superseded by run3
        //   - flow3 ends with Final("response 3") (round3 was the latest pending and got picked up by postRound)
        val provider = SlowFirstLlmProvider(
            responses = listOf(
                ChatResponse(
                    message = ChatMessage.Assistant(content = "response 1"),
                    finishReason = FinishReason.Stop,
                ),
                ChatResponse(
                    message = ChatMessage.Assistant(content = "response 3"),
                    finishReason = FinishReason.Stop,
                ),
            ),
            firstCallDelayMs = 500,
        )
        val (boss, _) = createBossAgent(provider = provider)

        // T1: launch run1 collector in a coroutine, do NOT await completion
        val flow1Events = mutableListOf<AgentEvent>()
        val flow1Job = launch {
            boss.run("first").collect { flow1Events.add(it) }
        }

        // Wait until state is RUNNING — deterministic, no arbitrary delay
        withTimeout(2000) {
            boss.state.first { it == BossState.RUNNING }
        }

        // T2: run2 — gets queued in pendingUserRound
        val flow2Events = mutableListOf<AgentEvent>()
        val flow2Job = launch {
            boss.run("second").collect { flow2Events.add(it) }
        }

        // Brief delay so run2's handlePending has committed pendingUserRound = round2
        // before run3 arrives. Without this, run3 might race ahead of run2 and the test
        // wouldn't actually exercise the supersede path.
        delay(50)

        // T3: run3 — supersedes round2 (which is in pendingUserRound)
        val flow3Events = mutableListOf<AgentEvent>()
        val flow3Job = launch {
            boss.run("third").collect { flow3Events.add(it) }
        }

        // Wait for all three flows to terminate
        withTimeout(5000) {
            flow1Job.join()
            flow2Job.join()
            flow3Job.join()
        }

        // flow2 must end with Failed(CancellationException("superseded by newer run()"))
        val flow2Last = flow2Events.lastOrNull()
        assertNotNull(flow2Last, "flow2 produced no events")
        assertTrue(
            flow2Last is AgentEvent.Failed,
            "expected flow2 to end with AgentEvent.Failed, got: $flow2Last"
        )
        val flow2Failed = flow2Last as AgentEvent.Failed
        assertTrue(
            flow2Failed.cause is CancellationException,
            "expected CancellationException, got: ${flow2Failed.cause::class.simpleName}"
        )
        assertEquals(
            "superseded by newer run()",
            flow2Failed.cause.message,
            "supersede CancellationException must carry the spec-mandated message"
        )

        // flow1 ends with Final("response 1") — round1 was running, never superseded
        val flow1Last = flow1Events.lastOrNull()
        assertTrue(
            flow1Last is AgentEvent.Final,
            "expected flow1 to end with AgentEvent.Final, got: $flow1Last"
        )
        assertEquals(
            ChatMessage.Assistant("response 1"),
            (flow1Last as AgentEvent.Final).result.message,
        )

        // flow3 ends with Final("response 3") — round3 was the latest pending and got picked up
        val flow3Last = flow3Events.lastOrNull()
        assertTrue(
            flow3Last is AgentEvent.Final,
            "expected flow3 to end with AgentEvent.Final, got: $flow3Last"
        )
        assertEquals(
            ChatMessage.Assistant("response 3"),
            (flow3Last as AgentEvent.Final).result.message,
        )

        assertEquals(BossState.WAITING, boss.state.value)
    }

    @Test
    fun `continuation flow receives events when terminal TaskUpdate arrives while WAITING`() = runBlocking {
        // 构造 idle 状态 + 派一个 task + 直接 publish 终态 TaskUpdate;
        // 验证 continuations 流收到 boss LLM 看到结果后发出的续轮 Final.
        val (boss, bb) = createBossAgent()

        // createBossAgent 已同步等到 BossAgent 订阅就绪, 直接 publish 即可.

        // 直接 publish 一个 taskId 的 TaskAssignment (跳过 PublishTaskTool)
        val taskId = "test-task-1"
        bb.publishEvent(
            TaskAssignment(
                taskId = taskId,
                selections = listOf(Selection.Tool("dummy")),
                task = "do something",
            )
        )

        // 订阅 continuations 后 publish 终态 TaskUpdate
        // 注: boss.continuations 是 SharedFlow (asSharedFlow() 返回的只读接口),
        // 不暴露 subscriptionCount — 此处 delay(100) 是 SharedFlow collector attach 的功能性等待,
        // 不可避免 (除非改 continuations 暴露 MutableSharedFlow 或包一层 wrapper).
        val continuations = mutableListOf<AgentEvent>()
        val job = launch { boss.continuations.collect { continuations.add(it) } }
        delay(100)  // 让 collector 订阅就位

        bb.progressEvent(
            TaskUpdate(taskId, AgentEvent.Final(AgentResult(ChatMessage.Assistant("done"), 1, emptyList(), null)))
        )

        // 等 boss 跑完续轮
        withTimeout(5000) {
            while (continuations.isEmpty()) delay(50)
        }
        // 再等 boss 回到 WAITING, 避免下面 job.cancel 时还在跑
        withTimeout(5000) {
            while (boss.state.value != BossState.WAITING) delay(50)
        }
        job.cancel()

        assertTrue(continuations.isNotEmpty(), "continuations was empty")
        assertTrue(continuations.any { it is AgentEvent.Final })
        assertEquals(BossState.WAITING, boss.state.value)
    }

    @Test
    fun `COLLECTING window — terminal update with active tasks enters COLLECTING then RUNNING`() = runBlocking {
        // 派 2 个 task (A, B). 给 A 发终态, B 不动.
        // BossAgent.handleTaskUpdate 看到 hasActive=true → handlePending 撞闲 + hasResults + hasActive
        //   → 第 3 段第 1 分支 (postRound=false, hasResults, hasActive) → COLLECTING 1s
        // 1s 后 postRound 决策 → 第 2 分支 (hasResults=true, postRound=true) → RUNNING 跑续轮.
        // 验证: state 经历 WAITING → COLLECTING → RUNNING → WAITING (无 RUNNING→WAITING→RUNNING 闪烁).
        val (boss, bb) = createBossAgent()

        // createBossAgent 已同步等到 BossAgent 订阅就绪, 直接 publish 即可.

        val taskIdA = "task-A"
        val taskIdB = "task-B"
        bb.publishEvent(TaskAssignment(taskIdA, listOf(Selection.Tool("t")), "A"))
        bb.publishEvent(TaskAssignment(taskIdB, listOf(Selection.Tool("t")), "B"))

        // 收集 state 变更
        val stateLog = mutableListOf<BossState>()
        val stateJob = launch { boss.state.collect { stateLog.add(it) } }

        // publish A 终态
        bb.progressEvent(TaskUpdate(taskIdA, AgentEvent.Final(AgentResult(ChatMessage.Assistant("A done"), 1, emptyList(), null))))

        // 等 state 进入 COLLECTING
        withTimeout(10000) {
            while (boss.state.value != BossState.COLLECTING) delay(20)
        }
        assertEquals(BossState.COLLECTING, boss.state.value)

        // 等 boss 跑完续轮 → WAITING
        withTimeout(5000) {
            while (boss.state.value != BossState.WAITING) delay(50)
        }

        stateJob.cancel()
        assertEquals(BossState.WAITING, boss.state.value)

        // 验证 state log 没出现 WAITING→RUNNING→WAITING→RUNNING 闪烁 — 只接受 WAITING→COLLECTING→RUNNING→WAITING
        val collapsed = stateLog.distinct()
        // collapsed 应该是 WAITING→COLLECTING→RUNNING→WAITING 子序列 (允许 RUNNING 后直接 WAITING)
        assertTrue(collapsed.contains(BossState.COLLECTING), "expected COLLECTING in state log: $collapsed")
    }

    @Test
    fun `postRound path does NOT enter COLLECTING even with pending results — anti-loop`() = runBlocking {
        // 跑完一轮后, 即使 pendingResultEvents 还有数据, handlePending(postRound=true)
        // 必须直接走 RUNNING (第 2 分支), 不能再进 COLLECTING (否则 1s collect 死循环).
        //
        // 构造: 先派一个 task, publish TaskUpdate(Final). 进入 COLLECTING 等 1s,
        // 等 1s 过后 postRound 接管 → 跑续轮. 续轮跑完再 postRound → idle → WAITING.
        // 期间不应再次进入 COLLECTING (即使有 pending).
        val (boss, bb) = createBossAgent()

        val taskId = "task-X"
        bb.publishEvent(TaskAssignment(taskId, listOf(Selection.Tool("t")), "X"))
        delay(200)

        val stateLog = mutableListOf<BossState>()
        val stateJob = launch { boss.state.collect { stateLog.add(it) } }

        bb.progressEvent(TaskUpdate(taskId, AgentEvent.Final(AgentResult(ChatMessage.Assistant("done"), 1, emptyList(), null))))

        // 等最终回到 WAITING
        withTimeout(5000) {
            while (boss.state.value != BossState.WAITING) delay(50)
        }
        // 再等一小段时间确认没有再次抖动
        delay(200)
        stateJob.cancel()

        // 验证 COLLECTING 只进入一次 (postRound 路径不会再进)
        val collectingCount = stateLog.count { it == BossState.COLLECTING }
        assertTrue(collectingCount <= 1, "COLLECTING entered $collectingCount times — postRound loop bug: $stateLog")
        assertEquals(BossState.WAITING, boss.state.value)
    }

    @Test
    fun `terminal TaskUpdate with no active tasks triggers immediate continuation (no COLLECTING)`() = runBlocking {
        // 派 1 个 task A → publish 终态 → handlePending 撞闲 + hasResults + !hasActive
        //   → 第 3 段第 2 分支 (hasResults=true) → RUNNING 直接跑续轮, 不进 COLLECTING.
        val (boss, bb) = createBossAgent()

        val taskId = "task-A"
        bb.publishEvent(TaskAssignment(taskId, listOf(Selection.Tool("t")), "A"))
        delay(200)

        val stateLog = mutableListOf<BossState>()
        val stateJob = launch { boss.state.collect { stateLog.add(it) } }

        bb.progressEvent(TaskUpdate(taskId, AgentEvent.Final(AgentResult(ChatMessage.Assistant("done"), 1, emptyList(), null))))

        // 等回 WAITING
        withTimeout(5000) {
            while (boss.state.value != BossState.WAITING) delay(20)
        }
        stateJob.cancel()

        // 不应经过 COLLECTING — 因为 hasActive=false
        assertTrue(!stateLog.contains(BossState.COLLECTING), "unexpected COLLECTING when no active tasks: $stateLog")
    }
}
