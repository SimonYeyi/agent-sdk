package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test
import kotlin.test.assertTrue

private val EchoTool = object : Tool {
    override val name = "echo"
    override val description = "Echo."
    override val parametersSchema = ToolParameters.Empty
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
        ToolExecutionResult("echoed")
}

class BossAgentIntegrationTest {

    private val BOSS_PUBLISH_CALL: ChatResponse = ChatResponse(
        message = ChatMessage.Assistant(
            content = "",
            toolCalls = listOf(
                ToolCall(
                    id = "c1",
                    name = "publish_task",
                    arguments = buildJsonObject {
                        putJsonArray("tasks") {
                            add(buildJsonObject {
                                putJsonArray("selections") {
                                    add(buildJsonObject {
                                        put("type", "tool")
                                        put("name", "echo")
                                    })
                                }
                                put("task", "say hello")
                            })
                        }
                    },
                )
            ),
        ),
        usage = null,
        finishReason = FinishReason.ToolCalls,
    )

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

    private val BEAST_FINAL: ChatResponse = ChatResponse(
        message = ChatMessage.Assistant(content = "done", toolCalls = emptyList()),
        usage = null,
        finishReason = FinishReason.Stop,
    )

    @Test
    fun `end-to-end — boss publishes task, beast runs, boss continuation flows to user`() = runBlocking {
        // boss LLM 决策序列: 1) 调 publish_task → 2) Final "已派活" → 3) 续轮 Final "结果如下"
        // beast LLM 决策序列: 1) Final (没 tool call)
        val toolReg = ToolRegistry().apply { register(EchoTool) }
        val capabilitiesByType: Map<String, List<NamedCapability>> = mapOf(
            "tool" to listOf(NamedCapability("echo", "Echo back the argument."))
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

        val bossLlm = FakeLlmProvider(
            nonStreamResponses = listOf(BOSS_PUBLISH_CALL, BOSS_WAITING, BOSS_CONTINUATION),
        )
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

        // 用 launch(UNDISPATCHED) 同步挂上 boss.continuations collector,无需 delay 屏障.
        val continuations = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.continuations.collect { continuations.add(it) }
        }

        // 用户输入 → boss 决策 → publish_task → 完成 round → state WAITING
        val userRoundEvents = boss.run("帮我跑 echo").toList()
        assertTrue(userRoundEvents.isNotEmpty())

        // beast 已 publish TaskAssignment → Pasture 跑 beast → TaskUpdate(Final) 回来
        // → handlePending 撞闲 + hasResults + !hasActive → 跑续轮 → continuations 收到
        withTimeout(3000) {
            while (continuations.isEmpty()) delay(50)
        }
        // 等回 WAITING 防止 race
        withTimeout(3000) {
            while (boss.state.value != BossState.WAITING) delay(50)
        }

        contJob.cancel()
        boss.shutdown()

        // 验证续轮至少含一个 Final (BEAST_FINAL 触发续轮, BOSS_CONTINUATION 发出 Final)
        assertTrue(continuations.any { it is AgentEvent.Final }, "no Final in continuations: $continuations")
    }

    @Test
    fun `end-to-end — boss publishes two concurrent tasks, both return`() = runBlocking {
        // 一次 publish_task tasks=[A, B] → 两个 TaskAssignment 并发跑 → 续轮看到两个结果
        // boss LLM 决策: 1) 调 publish_task(tasks=[A, B]) → 2) Final "已派两个" → 3) 续轮 Final
        val toolReg = ToolRegistry().apply { register(EchoTool) }
        val capabilitiesByType: Map<String, List<NamedCapability>> = mapOf(
            "tool" to listOf(NamedCapability("echo", "Echo."))
        )

        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val assembler = BeastAssembler(
            llmProvider = FakeLlmProvider(nonStreamResponses = listOf(BEAST_FINAL, BEAST_FINAL)),
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

        val twoTaskCall = ChatResponse(
            message = ChatMessage.Assistant(
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "c1",
                        name = "publish_task",
                        arguments = buildJsonObject {
                            putJsonArray("tasks") {
                                add(buildJsonObject {
                                    putJsonArray("selections") {
                                        add(buildJsonObject {
                                            put("type", "tool")
                                            put("name", "echo")
                                        })
                                    }
                                    put("task", "A")
                                })
                                add(buildJsonObject {
                                    putJsonArray("selections") {
                                        add(buildJsonObject {
                                            put("type", "tool")
                                            put("name", "echo")
                                        })
                                    }
                                    put("task", "B")
                                })
                            }
                        },
                    )
                ),
            ),
            usage = null,
            finishReason = FinishReason.ToolCalls,
        )

        val bossLlm = FakeLlmProvider(
            nonStreamResponses = listOf(twoTaskCall, BOSS_WAITING, BOSS_CONTINUATION, BOSS_CONTINUATION),
        )
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

        val continuations = mutableListOf<AgentEvent>()
        val contJob = launch(start = CoroutineStart.UNDISPATCHED) {
            boss.continuations.collect { continuations.add(it) }
        }

        boss.run("并发派 A 和 B").toList()

        // 两个并发 task 都应 Final; 续轮触发后直接 RUNNING
        // 先等续轮有内容 (state 可能在 RUNNING 中, 不能只看 WAITING)
        withTimeout(5000) {
            while (continuations.isEmpty()) delay(50)
        }
        // 再等回 WAITING 防止 race
        withTimeout(3000) {
            while (boss.state.value != BossState.WAITING) delay(50)
        }

        contJob.cancel()
        boss.shutdown()

        // 续轮至少发生一次
        assertTrue(continuations.isNotEmpty(), "no continuations: $continuations")
    }
}
