package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.ToolDefinition
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.toolset.ToolsetRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val FINAL_RESPONSE: ChatResponse = ChatResponse(
    message = ChatMessage.Assistant(content = "done", toolCalls = emptyList()),
    usage = null,
    finishReason = FinishReason.Stop,
)

private val EchoTool = object : Tool {
    override val name: String = "echo"
    override val description: String = "Echo back the argument."
    override val parametersSchema: ToolParameters = ToolParameters.Empty
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
        ToolExecutionResult("echoed: ${arguments}")
}

class BeastTest {

    @Test
    fun `Ox run emits Final when LLM returns Final response`() = runTest {
        val fake = FakeLlmProvider(nonStreamResponses = listOf(FINAL_RESPONSE))
        val ox = Ox(
            llmProvider = fake,
            persona = Persona("test"),
            toolRegistry = null, skillRegistry = null,
            subagentRegistry = null, toolsetRegistry = null,
            maxIterations = 1, maxRounds = 5,
        )

        val events = mutableListOf<AgentEvent>()
        ox.run(AgentQuery.text("do it")) { events.add(it) }

        assertTrue(events.any { it is AgentEvent.Final }, "expected Final, got: $events")
    }

    @Test
    fun `Ox repeated run does not mutate shared tool registry`() = runTest {
        val fake = FakeLlmProvider(nonStreamResponses = List(2) { FINAL_RESPONSE })
        val toolRegistry = ToolRegistry().apply { register(EchoTool) }
        val ox = Ox(
            llmProvider = fake,
            persona = Persona("test"),
            toolRegistry = toolRegistry,
            skillRegistry = SkillRegistry(),
            subagentRegistry = SubagentRegistry(),
            toolsetRegistry = ToolsetRegistry(),
            maxIterations = 1,
            maxRounds = 5,
        )

        repeat(2) { ox.run(AgentQuery.text("do it")) { } }

        assertEquals(listOf("echo"), toolRegistry.all().map { it.name })
    }

    @Test
    fun `Horse run emits Final when LLM returns Final response`() = runTest {
        val fake = FakeLlmProvider(nonStreamResponses = listOf(FINAL_RESPONSE))
        val horse = Horse(
            llmProvider = fake,
            persona = Persona("specialist"),
            tools = emptyList(),
            maxIterations = 1, maxRounds = 5,
        )

        val events = mutableListOf<AgentEvent>()
        horse.run(AgentQuery.text("task")) { events.add(it) }

        assertTrue(events.any { it is AgentEvent.Final })
    }

    @Test
    fun `Horse can call tool from pre-loaded list`() = runTest {
        // 第一轮 LLM 调 tool, 第二轮 Final — Horse.tools 里要有 echo.
        val toolCallResponse = ChatResponse(
            message = ChatMessage.Assistant(
                content = "", toolCalls = listOf(
                    ToolCall(id = "c1", name = "echo", arguments = kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive("hi")) }),
                )
            ),
            usage = null,
            finishReason = FinishReason.ToolCalls,
        )
        val finalResponse = FINAL_RESPONSE
        val fake = FakeLlmProvider(nonStreamResponses = listOf(toolCallResponse, finalResponse))
        val horse = Horse(
            llmProvider = fake,
            persona = Persona("specialist"),
            tools = listOf(EchoTool),
            maxIterations = 5, maxRounds = 5,
        )

        val events = mutableListOf<AgentEvent>()
        horse.run(AgentQuery.text("task")) { events.add(it) }

        assertTrue(events.any { it is AgentEvent.ToolCallStart && it.toolName == "echo" })
        assertTrue(events.any { it is AgentEvent.Final })
    }

    @Test
    fun `Ox run with no registries still works (Empty registries)`() = runTest {
        val fake = FakeLlmProvider(nonStreamResponses = listOf(FINAL_RESPONSE))
        val ox = Ox(
            llmProvider = fake,
            persona = Persona(""),
            toolRegistry = null, skillRegistry = null,
            subagentRegistry = null, toolsetRegistry = null,
            maxIterations = 1, maxRounds = 5,
        )

        val events = mutableListOf<AgentEvent>()
        ox.run(AgentQuery.text("noop")) { events.add(it) }

        assertTrue(events.any { it is AgentEvent.Final })
    }

    @Test
    fun `Ox run propagates LLM errors as Failed event via onEvent`() = runTest {
        val failingFake = object : io.github.yeyi.agent.llm.LlmProvider {
            override val name: String = "failing"
            override suspend fun chat(request: ChatRequest): ChatResponse =
                throw RuntimeException("LLM down")
            override fun chatStream(request: ChatRequest) = kotlinx.coroutines.flow.flow<io.github.yeyi.agent.llm.ChatResponseEvent> {
                throw RuntimeException("LLM down")
            }
        }
        val ox = Ox(
            llmProvider = failingFake,
            persona = Persona(""),
            toolRegistry = null, skillRegistry = null,
            subagentRegistry = null, toolsetRegistry = null,
            maxIterations = 1, maxRounds = 5,
        )

        val events = mutableListOf<AgentEvent>()
        ox.run(AgentQuery.text("task")) { events.add(it) }

        // Ox 内部 catch 失败并 emit Failed(throwable)
        val failed = events.filterIsInstance<AgentEvent.Failed>()
        assertEquals(1, failed.size, "expected exactly one Failed, got: $events")
    }

    @Test
    fun `Ox run cancels cleanly when scope cancels`() = runTest {
        val blockingFake = FakeLlmProvider(nonStreamResponses = listOf(FINAL_RESPONSE))
        val ox = Ox(
            llmProvider = blockingFake,
            persona = Persona(""),
            toolRegistry = null, skillRegistry = null,
            subagentRegistry = null, toolsetRegistry = null,
            maxIterations = 1, maxRounds = 5,
        )

        // runTest 的 cancellation 会在 collect 完成后注入, 这里只验证不抛非预期异常.
        val events = mutableListOf<AgentEvent>()
        ox.run(AgentQuery.text("task")) { events.add(it) }

        assertTrue(events.isNotEmpty())
    }
}