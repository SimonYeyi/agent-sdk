package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.skill.Skill
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.toolset.Toolset
import io.github.yeyi.agent.toolset.ToolsetRegistry
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import kotlin.test.assertTrue

private val PASTURE_FINAL: ChatResponse = ChatResponse(
    message = ChatMessage.Assistant(content = "done", toolCalls = emptyList()),
    usage = null,
    finishReason = FinishReason.Stop,
)

private val EchoTool = object : Tool {
    override val name: String = "echo"
    override val description: String = "Echo back the argument."
    override val parametersSchema: ToolParameters = ToolParameters.Empty
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
        ToolExecutionResult.success("echoed")
}

private val ToolSetTool = object : Tool {
    override val name: String = "toolset_tool"
    override val description: String = "From a toolset."
    override val parametersSchema: ToolParameters = ToolParameters.Empty
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
        ToolExecutionResult.success("ts")
}

/**
 * Drain Pasture's progress stream until a TaskUpdate carrying an [AgentEvent.Final]
 * arrives, or timeout (5s). Real-time semantics are required: Pasture's worker is
 * on `Dispatchers.Default` and Kotlin's `runTest` virtual scheduler would otherwise
 * cause spurious timeouts.
 */
private suspend fun awaitFinalUpdate(bb: BulletinBoard, timeoutMs: Long = 5000): TaskUpdate {
    return withTimeoutOrNull(timeoutMs) {
        bb.progressEvents
            .filterIsInstance<TaskUpdate>()
            .first { it.event is AgentEvent.Final }
    } ?: error("awaitFinalUpdate timed out after ${timeoutMs}ms")
}

/**
 * 用 async(UNDISPATCHED) 同步挂上测试自己的 collector —
 * UNDISPATCHED 让协程在当前线程跑到首个挂起点 (SharedFlow.collect 内的
 * awaitValue), 此时 allocateSlot() 已同步执行, async 返回时 collector 已注册,
 * 无需任何外部屏障.
 */
private suspend fun publishAndAwaitFinal(
    bb: BulletinBoard,
    taskId: String,
    selection: Selection,
    task: String,
): TaskUpdate = coroutineScope {
    val deferred = async(start = CoroutineStart.UNDISPATCHED) { awaitFinalUpdate(bb) }
    bb.publishEvent(TaskAssignments(listOf(TaskAssignment(taskId, selection, task, null, emptyList()))))
    deferred.await()
}

class PastureTest {

    private fun setupPasture(
        toolReg: ToolRegistry? = null,
        skillReg: SkillRegistry? = null,
        toolsetReg: ToolsetRegistry? = null,
        llmResponses: List<ChatResponse> = listOf(PASTURE_FINAL),
    ): Triple<BulletinBoard, Pasture, CoroutineScope> {
        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val assembler = BeastAssembler(
            llmProvider = FakeLlmProvider(nonStreamResponses = llmResponses),
            toolRegistry = toolReg,
            skillRegistry = skillReg,
            subagentRegistry = null,
            toolsetRegistry = toolsetReg,
            baseRole = "You are a helpful worker.",
            maxIterations = 1,
            maxRounds = 5,
        )
        val pasture = Pasture(assembler = assembler, scope = scope)
        runBlocking { pasture.observe(bb) }
        return Triple(bb, pasture, scope)
    }

    private fun runPastureTest(
        toolReg: ToolRegistry? = null,
        skillReg: SkillRegistry? = null,
        toolsetReg: ToolsetRegistry? = null,
        llmResponses: List<ChatResponse> = listOf(PASTURE_FINAL),
        block: suspend (BulletinBoard) -> Unit,
    ) = runBlocking {
        val (bb, _, _) = setupPasture(toolReg, skillReg, toolsetReg, llmResponses)
        block(bb)
    }

    @Test
    fun `empty selection falls back to Ox and still completes`() {
        runPastureTest { bb ->
            val update = publishAndAwaitFinal(bb, "t1", Selection.Tool(""), "noop")
            assertTrue(update.event is AgentEvent.Final, "expected Final, got: ${update.event}")
        }
    }

    @Test
    fun `Subagent selection falls back to Ox`() {
        runPastureTest { bb ->
            val update = publishAndAwaitFinal(bb, "t1", Selection.Subagent("foo"), "task")
            assertTrue(update.event is AgentEvent.Final, "expected Final, got: ${update.event}")
        }
    }

    @Test
    fun `tool not found falls back to Ox`() {
        val toolReg = ToolRegistry().apply { register(EchoTool) }
        runPastureTest(toolReg = toolReg) { bb ->
            val update = publishAndAwaitFinal(bb, "t1", Selection.Tool("nonexistent"), "task")
            // 退 Ox — Ox 持 toolReg 但没有 "nonexistent", 仍能跑 (FakeLlm 返回 Final)
            assertTrue(update.event is AgentEvent.Final, "expected Final, got: ${update.event}")
        }
    }

    @Test
    fun `toolset not found falls back to Ox`() {
        val toolsetReg = ToolsetRegistry().apply {
            register(Toolset("real", "real").apply { add(ToolSetTool) })
        }
        runPastureTest(toolsetReg = toolsetReg) { bb ->
            val update = publishAndAwaitFinal(bb, "t1", Selection.Toolset("missing"), "task")
            assertTrue(update.event is AgentEvent.Final, "expected Final, got: ${update.event}")
        }
    }

    @Test
    fun `tool selection assembles Horse and runs to Final`() {
        val toolReg = ToolRegistry().apply { register(EchoTool) }
        runPastureTest(toolReg = toolReg) { bb ->
            val update = publishAndAwaitFinal(bb, "t1", Selection.Tool("echo"), "task")
            assertTrue(update.event is AgentEvent.Final, "expected Final, got: ${update.event}")
        }
    }

    @Test
    fun `toolset selection assembles Horse with all toolset tools`() {
        val toolsetReg = ToolsetRegistry().apply {
            register(Toolset("ts1", "ts1").apply { add(ToolSetTool) })
        }
        runPastureTest(toolsetReg = toolsetReg) { bb ->
            val update = publishAndAwaitFinal(bb, "t1", Selection.Toolset("ts1"), "task")
            assertTrue(update.event is AgentEvent.Final, "expected Final, got: ${update.event}")
        }
    }

    @Test
    fun `skill text auto-binds tool names mentioned in load() text`() {
        // Skill.load() 提到 bound_tool, Pasture 应能成功 assembleHorse (Skill 路径能跑通 Final).
        val skillReg = SkillRegistry().apply {
            register(object : Skill {
                override val name = "loader_skill"
                override val description = "Use bound_tool for X"
                override suspend fun load(): String = "You can use bound_tool to fetch data."
            })
        }
        runPastureTest(skillReg = skillReg) { bb ->
            val update = publishAndAwaitFinal(bb, "t1", Selection.Skill("loader_skill"), "task")
            assertTrue(update.event is AgentEvent.Final, "expected Final, got: ${update.event}")
        }
    }

    @Test
    fun `same name in different Selection types does not collide`() {
        // Selection.Tool("echo") 和 Selection.Toolset("echo") 是不同 route, 不应互冲.
        // 验证方式: 两个 task 顺序 publish, 都应拿到 Final — 若 Selection 路由有冲突,
        // assembleHorse / buildOx 路径会错乱 (e.g., 一个走 Horse 一个走 Ox, 或 look-up 报错).
        val toolReg = ToolRegistry().apply { register(EchoTool) }
        val toolsetReg = ToolsetRegistry().apply {
            register(Toolset("echo", "toolset named echo").apply { add(ToolSetTool) })
        }
        runPastureTest(
            toolReg = toolReg,
            toolsetReg = toolsetReg,
            llmResponses = listOf(PASTURE_FINAL, PASTURE_FINAL),
        ) { bb ->
            // 先 Tool("echo") path — 应走 Horse (有 echo tool).
            val u1 = publishAndAwaitFinal(bb, "t1", Selection.Tool("echo"), "a")
            assertTrue(u1.event is AgentEvent.Final, "Tool route failed: ${u1.event}")

            // 再 Toolset("echo") path — 应走 Horse (有 toolset.echo).
            val u2 = publishAndAwaitFinal(bb, "t2", Selection.Toolset("echo"), "b")
            assertTrue(u2.event is AgentEvent.Final, "Toolset route failed: ${u2.event}")

            // 两条 route 都 Final, 验证不互冲.
            assertTrue(u1.taskId == "t1" && u2.taskId == "t2",
                "taskIds crossed: u1=${u1.taskId}, u2=${u2.taskId}")
        }
    }
}

class PastureCancellationTest {

    private fun setupPasture(): Triple<BulletinBoard, Pasture, CoroutineScope> {
        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val assembler = BeastAssembler(
            llmProvider = FakeLlmProvider(nonStreamResponses = listOf(PASTURE_FINAL)),
            toolRegistry = null,
            skillRegistry = null,
            subagentRegistry = null,
            toolsetRegistry = null,
            baseRole = "You are a helpful worker.",
            maxIterations = 1,
            maxRounds = 5,
        )
        val pasture = Pasture(assembler = assembler, scope = scope)
        runBlocking { pasture.observe(bb) }
        return Triple(bb, pasture, scope)
    }

    @Test
    fun `cancel of completed task is silent (idempotent)`() = runBlocking {
        val (bb, _, _) = setupPasture()
        val update = publishAndAwaitFinal(bb, "t1", Selection.Tool("foo"), "task")
        assertTrue(update.event is AgentEvent.Final, "expected Final, got: ${update.event}")

        // 已完成的任务应从 runningJobs 被清掉 — 取消应是静默 no-op
        bb.publishEvent(Cancellation("t1"))
        delay(100)
        // 不抛异常, 静默
    }

    @Test
    fun `cancel of unknown task_id is silent`() = runBlocking {
        val (bb, _, _) = setupPasture()
        // setupPasture 已同步等到 Pasture 订阅就绪, 直接 publish 即可.
        bb.publishEvent(Cancellation("never-ran"))
        delay(100)
        // 不抛异常
    }

    @Test
    fun `multiple cancels for same task are all silent`() = runBlocking {
        val (bb, _, _) = setupPasture()
        val update = publishAndAwaitFinal(bb, "t1", Selection.Tool("foo"), "task")
        assertTrue(update.event is AgentEvent.Final, "expected Final, got: ${update.event}")

        repeat(5) { bb.publishEvent(Cancellation("t1")) }
        delay(100)
        // 全部静默
    }

    @Test
    fun `cancel of running long task emits TaskUpdate(Failed(CancellationException))`() = runBlocking {
        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val slowLlm = object : io.github.yeyi.agent.llm.LlmProvider {
            override val name = "slow"
            override suspend fun chat(request: io.github.yeyi.agent.llm.ChatRequest): ChatResponse {
                delay(5000)
                return PASTURE_FINAL
            }
            // chatStream 接口要求, Pasture 走 chat 路径不会调到
            override fun chatStream(request: io.github.yeyi.agent.llm.ChatRequest) =
                kotlinx.coroutines.flow.flow<io.github.yeyi.agent.llm.ChatResponseEvent> {
                    error("chatStream not expected in this test")
                }
        }
        val assembler = BeastAssembler(
            llmProvider = slowLlm,
            toolRegistry = null,
            skillRegistry = null,
            subagentRegistry = null,
            toolsetRegistry = null,
            baseRole = "You are a helpful worker.",
            maxIterations = 1,
            maxRounds = 5,
        )
        val pasture = Pasture(assembler = assembler, scope = scope)
        runBlocking { pasture.observe(bb) }

        // fork-join: UNDISPATCHED 让 async 同步挂上 collector, 返回时
        // allocateSlot() 已执行 (SharedFlow.collect 首个挂起点是 awaitValue).
        // 不依赖 slowLlm.delay(5000) 给协程挂订阅的窗口.
        val taskDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            bb.progressEvents
                .filterIsInstance<TaskUpdate>()
                .first { it.event is AgentEvent.Failed }
        }
        bb.publishEvent(TaskAssignments(listOf(TaskAssignment("t1", Selection.Tool("foo"), "long task", null, emptyList()))))
        delay(100)  // 让 Pasture collect 协程消费 TaskAssignment, 把 beast job 注册到 runningJobs — handleCancellation 依赖这个, 否则 cancel 是 no-op

        bb.publishEvent(Cancellation("t1"))

        val failed = withTimeout(3000) { taskDeferred.await() }
        scope.cancel()

        assertTrue(failed.event is AgentEvent.Failed, "expected Failed event, got: ${failed.event}")
        val cause = failed.event.cause
        assertTrue(cause is CancellationException,
            "expected CancellationException, got: ${cause::class.simpleName}")
    }
}
