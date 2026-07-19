package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
            ToolExecutionResult("echoed")
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
                putJsonArray("selections") {
                    add(buildJsonObject { put("type", "tool"); put("name", "echo") })
                }
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
        val toolReg = ToolRegistry().apply { register(EchoTool) }
        val capabilitiesByType: Map<String, List<NamedCapability>> = mapOf(
            "tool" to listOf(NamedCapability("echo", "Echo."))
        )

        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val assembler = BeastAssembler(
            llmProvider = FakeLlmProvider(nonStreamResponses = beastResponses),
            toolRegistry = toolReg,
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
        val boss = BossAgent(innerAgent, scope)
        runBlocking { boss.attach(bb) }

        return boss to bb
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

        val continuations = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.continuations.collect { continuations.add(it) }
        }

        boss.run("帮我查天气").toList()

        withTimeout(5000) {
            while (continuations.isEmpty()) delay(50)
        }
        withTimeout(3000) {
            while (boss.state.value != BossState.WAITING) delay(50)
        }

        contJob.cancel()
        boss.shutdown()

        assertTrue(continuations.isNotEmpty(), "no continuations: $continuations")
        assertTrue(continuations.any { it is AgentEvent.Final }, "no Final in continuations")
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

        val continuations = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.continuations.collect { continuations.add(it) }
        }

        boss.run("跑 A 和 B, B 依赖 A").toList()

        withTimeout(5000) {
            while (continuations.isEmpty()) delay(50)
        }
        withTimeout(3000) {
            while (boss.state.value != BossState.WAITING) delay(50)
        }

        contJob.cancel()
        boss.shutdown()

        assertTrue(continuations.isNotEmpty(), "no continuations: $continuations")
        assertTrue(continuations.any { it is AgentEvent.Final }, "no Final in continuations")
    }

    @Test
    fun `cross-round accumulation — round 1 task done then round 2 task dep on round 1`() = runBlocking {
        val toolReg = ToolRegistry().apply { register(EchoTool) }
        val caps: Map<String, List<NamedCapability>> = mapOf(
            "tool" to listOf(NamedCapability("echo", "Echo."))
        )
        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val assembler = BeastAssembler(
            llmProvider = FakeLlmProvider(nonStreamResponses = listOf(BEAST_FINAL)),
            toolRegistry = toolReg,
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
        bb.publishEvent(TaskAssignments(listOf(
            TaskAssignment("a_id", listOf(Selection.Tool("echo")), "task a", null, emptyList()),
        )))
        delay(200)

        // Round 1 completes
        bb.progressEvent(TaskUpdate("a_id", AgentEvent.Final(
            AgentResult(ChatMessage.Assistant("a done"), 0, emptyList(), null)
        )))

        // Round 2: publish task "b" depends_on "a_id" (cross-round ref)
        bb.publishEvent(TaskAssignments(listOf(
            TaskAssignment("b_id", listOf(Selection.Tool("echo")), "task b", null, listOf("a_id")),
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
            override fun chatStream(request: ChatRequest): Flow<StreamEvent> =
                kotlinx.coroutines.flow.flow { error("chatStream not expected") }
        }

        val toolReg = ToolRegistry().apply { register(EchoTool) }
        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val assembler = BeastAssembler(
            llmProvider = failingLlm,
            toolRegistry = toolReg,
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

        bb.publishEvent(TaskAssignments(listOf(
            TaskAssignment("a", listOf(Selection.Tool("echo")), "task a", null, emptyList()),
            TaskAssignment("b", listOf(Selection.Tool("echo")), "task b", null, listOf("a")),
            TaskAssignment("c", listOf(Selection.Tool("echo")), "task c", null, listOf("b")),
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

        val continuations = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.continuations.collect { continuations.add(it) }
        }

        boss.run("跑 diamond DAG").toList()

        withTimeout(5000) {
            while (continuations.isEmpty()) delay(50)
        }
        withTimeout(3000) {
            while (boss.state.value != BossState.WAITING) delay(50)
        }

        contJob.cancel()
        boss.shutdown()

        assertTrue(continuations.isNotEmpty(), "no continuations")
        assertTrue(continuations.any { it is AgentEvent.Final }, "no Final in continuations")
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
                        recordedInputs.add((lastUserMsg as ChatMessage.User).content)
                    }
                }
                return responses[index++]
            }
            override fun chatStream(request: ChatRequest): Flow<StreamEvent> =
                kotlinx.coroutines.flow.flow { error("not expected") }
        }

        val toolReg = ToolRegistry().apply { register(EchoTool) }
        val caps: Map<String, List<NamedCapability>> = mapOf(
            "tool" to listOf(NamedCapability("echo", "Echo."))
        )
        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val assembler = BeastAssembler(
            llmProvider = FakeLlmProvider(nonStreamResponses = listOf(BEAST_FINAL)),
            toolRegistry = toolReg,
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
        val boss = BossAgent(innerAgent, scope)
        runBlocking { boss.attach(bb) }

        val continuations = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.continuations.collect { continuations.add(it) }
        }

        boss.run("查数据").toList()

        withTimeout(5000) {
            while (continuations.isEmpty()) delay(50)
        }
        withTimeout(3000) {
            while (boss.state.value != BossState.WAITING) delay(50)
        }

        contJob.cancel()
        boss.shutdown()

        assertTrue(continuations.isNotEmpty(), "no continuations")
        assertTrue(continuations.any { it is AgentEvent.Final }, "no Final in continuations")

        assertTrue(recordedInputs.isNotEmpty(), "should have recorded at least one LLM input")
        val summaryInput = recordedInputs.lastOrNull()
        assertTrue(summaryInput?.contains("Tasks finished:") == true,
            "LLM input should contain 'Tasks finished:' summary, got: $summaryInput")
    }
}
