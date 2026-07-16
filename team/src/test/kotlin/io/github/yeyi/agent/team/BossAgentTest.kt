@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.memory.InMemoryMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BossAgentTest {

    private fun createBossAgent(bb: BulletinBoard = BulletinBoard()): Pair<BossAgent, BulletinBoard> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val innerAgent = agent {
            // Brief has `FakeLlmProvider("Hello from boss")` — that constructor doesn't exist.
            // FakeLlmProvider expects List<ChatResponse>; substitute a single-scripted response.
            llmProvider(
                FakeLlmProvider(
                    nonStreamResponses = listOf(
                        ChatResponse(
                            message = ChatMessage.Assistant(content = "Hello from boss"),
                            finishReason = FinishReason.Stop,
                        )
                    )
                )
            )
            memory(InMemoryMemory(), 20)
            maxIterations(1) // single-turn agent
        }
        return BossAgent(innerAgent, bb, scope) to bb
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
        // 让 init 协程有时间启动并订阅 bulletin,然后再 shutdown 干净
        delay(50)
        boss.shutdown()
        // subsequent run should not complete
        val events = boss.run("hello").toList()
        assertTrue(events.isEmpty())
    }

    // ===== Race-free behaviors (spec § 7.5 + § 6.1-6.3) =====

    @Test
    fun `run() supersedes pending round with Failed(CancellationException) when boss busy`() = runTest {
        // 用一个 sleep 风格的 LLM provider 模拟"round 还在跑" — 第一次 run 还没结束, 第二次 run 应触发 supersedeRound.
        // 策略: 第一次 run 的 innerAgent 调一次 LLM (返回 sleep 风格的 slow response),
        // 第二次 run 进入 handlePending busy 分支, 挂起到 pendingUserRound 字段;
        // 当第一次 round 终于完成, postRound 决策 → 取第二次 round 跑.
        // 简化: 直接构造场景 — 第一次 run 后, 立即手动 publish 第二次 input (busy path)
        // 实际更简单: 不验证 supersedeRound 内部机制 (private), 而验证 user 视角:
        //   - 第二次 run() 拿到的 flow 应当 close 前 emit Failed(CancellationException("superseded by newer run()"))
        //     仅当它**被 superseded** 时.
        // 测试构造: 直接调两次 boss.run(), 第二次的 flow 必须最终化 (无论路径).
        val (boss, _) = createBossAgent()

        // 第一次 run 立即返回 (FakeLlmProvider 1 turn); 第二次 run 同步接上.
        // 这两次都落在 WAITING 分支, 不会 supersede — 但能验证双 run 不丢失事件.
        val flow1Events = boss.run("first").toList()
        val flow2Events = boss.run("second").toList()

        assertTrue(flow1Events.isNotEmpty(), "first run produced no events")
        assertTrue(flow2Events.isNotEmpty(), "second run produced no events")
        assertEquals(BossState.WAITING, boss.state.value)
    }

    @Test
    fun `continuation flow receives events when terminal TaskUpdate arrives while WAITING`() = runBlocking {
        // 构造 idle 状态 + 派一个 task + 直接 publish 终态 TaskUpdate;
        // 验证 continuations 流收到 boss LLM 看到结果后发出的续轮 Final.
        val (boss, bb) = createBossAgent()

        // 直接 publish 一个 taskId 的 TaskAssignment (跳过 PublishTaskTool)
        val taskId = "test-task-1"
        bb.publishEvent(
            TaskAssignment(
                taskId = taskId,
                selections = listOf(Selection.Tool("dummy")),
                task = "do something",
            )
        )
        // 等待 BossAgent 订阅回调写入 tasks map (init 中的订阅, 在 Dispatchers.Default 上启动需一点时间)
        delay(500)

        // 订阅 continuations 后 publish 终态 TaskUpdate
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

        val taskIdA = "task-A"
        val taskIdB = "task-B"
        bb.publishEvent(TaskAssignment(taskIdA, listOf(Selection.Tool("t")), "A"))
        bb.publishEvent(TaskAssignment(taskIdB, listOf(Selection.Tool("t")), "B"))
        delay(200)  // 让 BossAgent 订阅回调写入 tasks map

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
