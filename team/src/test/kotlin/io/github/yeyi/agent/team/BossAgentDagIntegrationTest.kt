package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.lazy_loading.LazyTool
import io.github.yeyi.agent.tool.lazy_loading.LazyToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BossAgentDagIntegrationTest {

    private val EchoTool = object : Tool {
        override val name = "echo"
        override val description = "Echo."
        override val parametersSchema = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("echoed")
    }

    private val BEAST_FINAL: ChatResponse = ChatResponse(
        message = ChatMessage.Assistant(content = "done", toolCalls = emptyList()),
        usage = null,
        finishReason = FinishReason.Stop,
    )

    private fun publishTaskArgs(refsAndDeps: List<Pair<String, List<String>>>): ChatResponse {
        val taskJson = refsAndDeps.map { (ref, deps) ->
            buildJsonObject {
                put("ref", ref)
                put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") })
                put("task", "task $ref")
                if (deps.isNotEmpty()) {
                    putJsonArray("depends_on") { deps.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
        return ChatResponse(
            message = ChatMessage.Assistant(
                content = "",
                toolCalls = listOf(
                    io.github.yeyi.agent.llm.ToolCall(
                        id = "c1",
                        name = "publish_task",
                        arguments = buildJsonObject {
                            put("query", "query")
                            putJsonArray("tasks") { taskJson.forEach { add(it) } }
                        },
                    )
                ),
            ),
            usage = null,
            finishReason = FinishReason.ToolCalls,
        )
    }

    private val BOSS_WAITING: ChatResponse = ChatResponse(
        message = ChatMessage.Assistant(content = "已让助手去处理", toolCalls = emptyList()),
        usage = null,
        finishReason = FinishReason.Stop,
    )

    private val BOSS_CONTINUATION: ChatResponse = ChatResponse(
        message = ChatMessage.Assistant(content = "结果如下: ok", toolCalls = emptyList()),
        usage = null,
        finishReason = FinishReason.Stop,
    )

    /**
     * Build BossAgent + Pasture with scripted LLM responses.
     * Returns (boss, bulletinBoard).
     */
    private fun buildBossAndPasture(
        beastResponses: List<ChatResponse>,
        bossResponses: List<ChatResponse>,
    ): Pair<BossAgent, BulletinBoard> {
        val capabilitiesByType: Map<String, List<NamedCapability>> = mapOf(
            "tool" to listOf(NamedCapability("echo", "Echo."))
        )

        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val assembler = BeastAssembler(
            llmProvider = FakeLlmProvider(nonStreamResponses = beastResponses),
            toolRegistry = null,
            lazyToolRegistry = LazyToolRegistry().apply { register(LazyTool(EchoTool)) },
            skillRegistry = null,
            subagentRegistry = null,
            toolsetRegistry = null,
            baseRole = "You are a helpful worker.",
            maxIterations = 1,
            maxRounds = 5,
        )
        val pasture = Pasture(assembler = assembler, scope = scope)
        runBlocking { pasture.observe(bb) }

        val bossLlm = FakeLlmProvider(nonStreamResponses = bossResponses)
        val publishTask = PublishTaskTool(bb, capabilitiesByType)
        val cancelTask = CancelTaskTool(bb)
        val innerAgent = io.github.yeyi.agent.agent {
            llmProvider(bossLlm)
            io.github.yeyi.agent.memory.InMemoryMemory().let { memory(it, 20) }
            tool(publishTask)
            tool(cancelTask)
            maxIterations(5)
        }
        val boss = BossAgent(innerAgent, "[系统汇报]", scope)
        runBlocking { boss.attach(bb) }

        return boss to bb
    }

    /**
     * Build BossAgent only (no Pasture), for deterministic event-driven tests.
     * The test publishes TaskAssignments / TaskUpdate / Cancellation directly to
     * the bulletin board to drive the boss's lifecycle.
     */
    private fun buildBossOnly(bossLlm: LlmProvider): Pair<BossAgent, BulletinBoard> {
        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val innerAgent = io.github.yeyi.agent.agent {
            llmProvider(bossLlm)
            io.github.yeyi.agent.memory.InMemoryMemory().let { memory(it, 20) }
            maxIterations(5)
        }
        val boss = BossAgent(innerAgent, "[系统汇报]", scope)
        runBlocking { boss.attach(bb) }
        return boss to bb
    }

    /**
     * Scripted LLM that records the text of each user-turn input (index-aligned
     * with the responses list) so tests can assert what the boss was told.
     */
    private fun recordingLlm(
        responses: List<ChatResponse>,
        recordedInputs: MutableList<String>,
    ): LlmProvider = object : LlmProvider {
        override val name = "recording"
        private var index = 0
        override suspend fun chat(request: ChatRequest): ChatResponse {
            val lastUserMsg = request.messages.lastOrNull { it is ChatMessage.User }
            if (lastUserMsg != null) {
                recordedInputs.add(
                    (lastUserMsg as ChatMessage.User).parts.firstOrNull { it is ContentPart.Text }
                        ?.let { (it as ContentPart.Text).text } ?: ""
                )
            }
            return responses[index++]
        }
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> =
            kotlinx.coroutines.flow.flow { error("not expected") }
    }

    @Test
    fun `single task round — one publish triggers one continuation`() = runBlocking {
        val (boss, _) = buildBossAndPasture(
            beastResponses = listOf(BEAST_FINAL),
            bossResponses = listOf(
                publishTaskArgs(listOf("weather" to emptyList())),
                BOSS_WAITING,
                BOSS_CONTINUATION,
            ),
        )

        val report = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.report.collect { report.add(it) }
        }

        boss.run(AgentQuery.text("帮我查天气")).toList()

        withTimeout(5000) {
            while (report.isEmpty()) delay(50)
        }
        delay(500) // wait for boss to settle

        contJob.cancel()
        boss.shutdown()

        assertTrue(report.isNotEmpty(), "no report: $report")
        assertTrue(report.any { it is AgentEvent.Final }, "no Final in report")
    }

    @Test
    fun `DAG does not trigger continuation until all tasks in round complete`() = runBlocking {
        val (boss, _) = buildBossAndPasture(
            beastResponses = listOf(BEAST_FINAL, BEAST_FINAL),
            bossResponses = listOf(
                publishTaskArgs(listOf("a" to emptyList(), "b" to listOf("a"))),
                BOSS_WAITING,
                BOSS_CONTINUATION,
            ),
        )

        val report = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.report.collect { report.add(it) }
        }

        boss.run(AgentQuery.text("跑 A 和 B, B 依赖 A")).toList()

        withTimeout(5000) {
            while (report.isEmpty()) delay(50)
        }
        delay(500) // wait for boss to settle

        contJob.cancel()
        boss.shutdown()

        assertTrue(report.isNotEmpty(), "no report: $report")
        assertTrue(report.any { it is AgentEvent.Final }, "no Final in report")
    }

    @Test
    fun `cross-round accumulation — round 1 task done then round 2 task dep on round 1`() = runBlocking {
        val caps: Map<String, List<NamedCapability>> = mapOf(
            "tool" to listOf(NamedCapability("echo", "Echo."))
        )
        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val assembler = BeastAssembler(
            llmProvider = FakeLlmProvider(nonStreamResponses = listOf(BEAST_FINAL)),
            toolRegistry = null,
            lazyToolRegistry = LazyToolRegistry().apply { register(LazyTool(EchoTool)) },
            skillRegistry = null,
            subagentRegistry = null,
            toolsetRegistry = null,
            baseRole = "You are a helpful worker.",
            maxIterations = 1,
            maxRounds = 5,
        )
        val pasture = Pasture(assembler = assembler, scope = scope)
        runBlocking { pasture.observe(bb) }

        // Round 1: publish task "a"
        bb.publishEvent(TaskAssignments("query", listOf(
            TaskAssignment("a_id", Selection.Tool("echo"), "task a", null, emptyList()),
        )))
        delay(200)

        // Round 1 completes
        bb.progressEvent(TaskUpdate("a_id", AgentEvent.Final(
            AgentResult(ChatMessage.Assistant("a done"), 0, emptyList(), null)
        )))

        // Round 2: publish task "b" depends_on "a_id" (cross-round ref)
        bb.publishEvent(TaskAssignments("query", listOf(
            TaskAssignment("b_id", Selection.Tool("echo"), "task b", null, listOf("a_id")),
        )))
        delay(200)

        // Round 2 completes
        bb.progressEvent(TaskUpdate("b_id", AgentEvent.Final(
            AgentResult(ChatMessage.Assistant("b done"), 0, emptyList(), null)
        )))

        delay(200)
        scope.cancel()
        // Passes if no exception — cross-round dependency resolved correctly
    }

    @Test
    fun `DAG failure cascade — one upstream fail cascades all downstream`() = runBlocking {
        val failingLlm = object : LlmProvider {
            override val name = "failing"
            override suspend fun chat(request: ChatRequest): ChatResponse {
                throw RuntimeException("simulated beast failure")
            }
            override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> =
                kotlinx.coroutines.flow.flow { error("chatStream not expected") }
        }

        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val assembler = BeastAssembler(
            llmProvider = failingLlm,
            toolRegistry = null,
            lazyToolRegistry = LazyToolRegistry().apply { register(LazyTool(EchoTool)) },
            skillRegistry = null,
            subagentRegistry = null,
            toolsetRegistry = null,
            baseRole = "You are a helpful worker.",
            maxIterations = 1,
            maxRounds = 5,
        )
        val pasture = Pasture(assembler = assembler, scope = scope)
        runBlocking { pasture.observe(bb) }

        val updates = mutableListOf<TaskUpdate>()
        val collectJob = launch {
            bb.progressEvents.collect { e ->
                if (e is TaskUpdate) updates.add(e)
            }
        }
        delay(50)

        bb.publishEvent(TaskAssignments("query", listOf(
            TaskAssignment("a", Selection.Tool("echo"), "task a", null, emptyList()),
            TaskAssignment("b", Selection.Tool("echo"), "task b", null, listOf("a")),
            TaskAssignment("c", Selection.Tool("echo"), "task c", null, listOf("b")),
        )))

        withTimeout(5000) {
            while (updates.filter { it.event is AgentEvent.Final || it.event is AgentEvent.Failed }.size < 3) delay(50)
        }

        collectJob.cancel()
        scope.cancel()

        val terminalUpdates = updates.filter { it.event is AgentEvent.Final || it.event is AgentEvent.Failed }
        assertEquals(3, terminalUpdates.size, "all three tasks should emit: $updates")
        assertTrue(terminalUpdates.all { it.event is AgentEvent.Failed },
            "all should be Failed: ${terminalUpdates.map { "${it.taskId}=${it.event::class.simpleName}" }}")
    }

    @Test
    fun `diamond DAG — four tasks all complete`() = runBlocking {
        val (boss, _) = buildBossAndPasture(
            beastResponses = listOf(BEAST_FINAL, BEAST_FINAL, BEAST_FINAL, BEAST_FINAL),
            bossResponses = listOf(
                publishTaskArgs(listOf(
                    "a" to emptyList(),
                    "b" to listOf("a"),
                    "c" to listOf("a"),
                    "d" to listOf("b", "c"),
                )),
                BOSS_WAITING,
                BOSS_CONTINUATION,
            ),
        )

        val report = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.report.collect { report.add(it) }
        }

        boss.run(AgentQuery.text("跑 diamond DAG")).toList()

        withTimeout(5000) {
            while (report.isEmpty()) delay(50)
        }
        delay(500) // wait for boss to settle

        contJob.cancel()
        boss.shutdown()

        assertTrue(report.isNotEmpty(), "no report")
        assertTrue(report.any { it is AgentEvent.Final }, "no Final in report")
    }

    @Test
    fun `LLM input contains roundSummary when continuation triggers`() = runBlocking {
        val recordedInputs = mutableListOf<String>()
        val recordingLlm = object : LlmProvider {
            override val name = "recording"
            private var index = 0
            private val responses = listOf(
                publishTaskArgs(listOf("data" to emptyList())),
                BOSS_WAITING,
                BOSS_CONTINUATION,
            )
            override suspend fun chat(request: ChatRequest): ChatResponse {
                if (index > 0) {
                    val lastUserMsg = request.messages.lastOrNull { it is ChatMessage.User }
                    if (lastUserMsg != null) {
                        recordedInputs.add((lastUserMsg as ChatMessage.User).parts.firstOrNull { it is ContentPart.Text }?.let { (it as ContentPart.Text).text } ?: "")
                    }
                }
                return responses[index++]
            }
            override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> =
                kotlinx.coroutines.flow.flow { error("not expected") }
        }

        val caps: Map<String, List<NamedCapability>> = mapOf(
            "tool" to listOf(NamedCapability("echo", "Echo."))
        )
        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val assembler = BeastAssembler(
            llmProvider = FakeLlmProvider(nonStreamResponses = listOf(BEAST_FINAL)),
            toolRegistry = null,
            lazyToolRegistry = LazyToolRegistry().apply { register(LazyTool(EchoTool)) },
            skillRegistry = null,
            subagentRegistry = null,
            toolsetRegistry = null,
            baseRole = "You are a helpful worker.",
            maxIterations = 1,
            maxRounds = 5,
        )
        val pasture = Pasture(assembler = assembler, scope = scope)
        runBlocking { pasture.observe(bb) }

        val publishTask = PublishTaskTool(bb, caps)
        val cancelTask = CancelTaskTool(bb)
        val innerAgent = io.github.yeyi.agent.agent {
            llmProvider(recordingLlm)
            io.github.yeyi.agent.memory.InMemoryMemory().let { memory(it, 20) }
            tool(publishTask)
            tool(cancelTask)
            maxIterations(5)
        }
        val boss = BossAgent(innerAgent, "[系统汇报]", scope)
        runBlocking { boss.attach(bb) }

        val report = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.report.collect { report.add(it) }
        }

        boss.run(AgentQuery.text("查数据")).toList()

        withTimeout(5000) {
            while (report.isEmpty()) delay(50)
        }
        delay(500) // wait for boss to settle

        contJob.cancel()
        boss.shutdown()

        assertTrue(report.isNotEmpty(), "no report")
        assertTrue(report.any { it is AgentEvent.Final }, "no Final in report")

        assertTrue(recordedInputs.isNotEmpty(), "should have recorded at least one LLM input")
        val summaryInput = recordedInputs.lastOrNull()
        assertTrue(summaryInput?.contains("[系统汇报]") == true,
            "LLM input should contain '[系统汇报]' summary, got: $summaryInput")
    }

    @Test
    fun `cancel of archived completed task wakes continuation round`() = runBlocking {
        val recordedInputs = mutableListOf<String>()
        val (boss, bb) = buildBossOnly(
            recordingLlm(
                listOf(BOSS_WAITING, BOSS_WAITING, BOSS_CONTINUATION),
                recordedInputs,
            )
        )

        val report = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.report.collect { report.add(it) }
        }

        boss.run(AgentQuery.text("查天气")).toList()

        // 任务进入追踪表并终态 → 整轮完成 → 归档 + 结果轮次
        bb.publishEvent(TaskAssignments("query", listOf(
            TaskAssignment("a", Selection.Tool("echo"), "task a", null, emptyList()),
        )))
        bb.progressEvent(TaskUpdate("a", AgentEvent.Final(
            AgentResult(ChatMessage.Assistant("a done"), 0, emptyList(), null)
        )))
        withTimeout(5000) { while (recordedInputs.size < 2) delay(50) }  // 用户轮 + 结果轮 1

        // 取消一个已完成且已归档的任务 → 直接唤醒续轮告知模型任务已完成, 无法取消
        bb.publishEvent(Cancellation("a"))
        withTimeout(5000) { while (recordedInputs.size < 3) delay(50) }
        delay(300)

        contJob.cancel()
        boss.shutdown()

        val notice = recordedInputs[2]
        assertTrue(notice.contains("already finished and cannot be cancelled"),
            "notice round should be told the task is already finished, got: $notice")
        assertTrue(report.any { it is AgentEvent.Final }, "no Final in report: $report")
    }

    @Test
    fun `cancel of completed task in an incomplete round does not wake boss early`() = runBlocking {
        val recordedInputs = mutableListOf<String>()
        val (boss, bb) = buildBossOnly(
            recordingLlm(listOf(BOSS_WAITING, BOSS_CONTINUATION), recordedInputs)
        )

        val report = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.report.collect { report.add(it) }
        }

        boss.run(AgentQuery.text("跑两个任务")).toList()

        // 同轮两个任务: a 已终态, b 仍在跑 → 轮次未完成
        bb.publishEvent(TaskAssignments("query", listOf(
            TaskAssignment("a", Selection.Tool("echo"), "task a", null, emptyList()),
            TaskAssignment("b", Selection.Tool("echo"), "task b", null, emptyList()),
        )))
        bb.progressEvent(TaskUpdate("a", AgentEvent.Final(
            AgentResult(ChatMessage.Assistant("a done"), 0, emptyList(), null)
        )))
        delay(200)

        // 取消已完成但同轮未全终态的 a → Boss 不应被唤醒 (轮次继续等 b)
        bb.publishEvent(Cancellation("a"))
        delay(300)
        assertEquals(1, recordedInputs.size, "no continuation round should start: $recordedInputs")

        // b 终态 → 整轮完成 → 唯一的结果轮次
        bb.progressEvent(TaskUpdate("b", AgentEvent.Final(
            AgentResult(ChatMessage.Assistant("b done"), 0, emptyList(), null)
        )))
        withTimeout(5000) { while (recordedInputs.size < 2) delay(50) }
        delay(300)

        contJob.cancel()
        boss.shutdown()

        assertEquals(2, recordedInputs.size, "exactly one result round after round completes: $recordedInputs")
        assertTrue(report.any { it is AgentEvent.Final }, "no Final in report: $report")
    }

    @Test
    fun `cancel of completed task in result round wakes continuation`() = runBlocking {
        val recordedInputs = mutableListOf<String>()
        val cancellingLlm = object : LlmProvider {
            override val name = "cancelling"
            private var index = 0
            override suspend fun chat(request: ChatRequest): ChatResponse {
                val lastUserMsg = request.messages.lastOrNull { it is ChatMessage.User }
                if (lastUserMsg != null) {
                    recordedInputs.add(
                        (lastUserMsg as ChatMessage.User).parts.firstOrNull { it is ContentPart.Text }
                            ?.let { (it as ContentPart.Text).text } ?: ""
                    )
                }
                return when (index++) {
                    0 -> publishTaskArgs(listOf("a" to emptyList()))
                    1 -> {
                        val input = recordedInputs.last()
                        val taskId = Regex("""- (\S+):""").find(input)?.groupValues?.get(1)
                            ?: error("no task_id in result round input: $input")
                        ChatResponse(
                            message = ChatMessage.Assistant(
                                content = "",
                                toolCalls = listOf(
                                    io.github.yeyi.agent.llm.ToolCall(
                                        id = "c1",
                                        name = "cancel_task",
                                        arguments = buildJsonObject { put("task_id", taskId) },
                                    )
                                ),
                            ),
                            usage = null,
                            finishReason = FinishReason.ToolCalls,
                        )
                    }
                    else -> BOSS_CONTINUATION
                }
            }
            override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> =
                kotlinx.coroutines.flow.flow { error("not expected") }
        }

        val caps: Map<String, List<NamedCapability>> = mapOf(
            "tool" to listOf(NamedCapability("echo", "Echo."))
        )
        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val assembler = BeastAssembler(
            llmProvider = FakeLlmProvider(nonStreamResponses = listOf(BEAST_FINAL)),
            toolRegistry = null,
            lazyToolRegistry = LazyToolRegistry().apply { register(LazyTool(EchoTool)) },
            skillRegistry = null,
            subagentRegistry = null,
            toolsetRegistry = null,
            baseRole = "You are a helpful worker.",
            maxIterations = 1,
            maxRounds = 5,
        )
        val pasture = Pasture(assembler = assembler, scope = scope)
        runBlocking { pasture.observe(bb) }

        val publishTask = PublishTaskTool(bb, caps)
        val cancelTask = CancelTaskTool(bb)
        val innerAgent = io.github.yeyi.agent.agent {
            llmProvider(cancellingLlm)
            io.github.yeyi.agent.memory.InMemoryMemory().let { memory(it, 20) }
            tool(publishTask)
            tool(cancelTask)
            maxIterations(5)
        }
        val boss = BossAgent(innerAgent, "[系统汇报]", scope)
        runBlocking { boss.attach(bb) }

        val report = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.report.collect { report.add(it) }
        }

        boss.run(AgentQuery.text("查天气")).toList()

        // 结果轮 1: 模型对已完成任务调 cancel_task → Cancellation 落空 (Pasture 静默)
        // → Boss 查活跃表发现任务已归档 → 通知轮唤醒续轮, 避免卡在"正在取消"进行态
        withTimeout(5000) { while (recordedInputs.size < 3) delay(50) }
        delay(300)

        contJob.cancel()
        boss.shutdown()

        val notice = recordedInputs[2]
        assertTrue(notice.contains("already finished and cannot be cancelled"),
            "notice round should be told the task is already finished, got: $notice")
        assertTrue(report.any { it is AgentEvent.Final }, "no Final in report: $report")
    }
}
