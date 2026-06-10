package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmClient
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentBuilderSkillExtTest {

    private class FakeTool(override val name: String) : Tool {
        override val description: String = ""
        override val parametersSchema = ToolParameters.Empty
        override suspend fun execute(args: JsonElement, ctx: ToolContext) =
            io.github.yeyi.agent.tool.ToolExecutionResult(content = "ok", isError = false)
    }

    /** A minimal LlmClient that records every request and returns a stop response. */
    private class RecordingLlm : LlmClient {
        override val providerName: String = "recording"
        val recorded: MutableList<ChatRequest> = mutableListOf()

        override suspend fun chat(request: ChatRequest): ChatResponse {
            recorded += request
            return ChatResponse(
                message = ChatMessage.Assistant(content = "ok"),
                finishReason = FinishReason.Stop,
            )
        }

        override fun chatStream(request: ChatRequest): Flow<io.github.yeyi.agent.llm.StreamEvent> = flow {
            recorded += request
            emit(io.github.yeyi.agent.llm.StreamEvent.Done(usage = null, finishReason = FinishReason.Stop))
        }
    }

    @Test
    fun `skill() makes a skill_ prefixed tool visible to the LLM`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmClient = llm }
        b.skill(Skill(name = "weather", description = "d", body = "B"))
        b.build().run("hi").toList()
        val req = llm.recorded.single()
        val toolNames = req.tools.map { it.name }
        assertTrue("skill_weather" in toolNames, "expected skill_weather in $toolNames")
    }

    @Test
    fun `skill() also exposes the skill's bundled tools to the LLM`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmClient = llm }
        b.skill(
            Skill(
                name = "weather",
                description = "d",
                body = "B",
                tools = listOf(FakeTool("get_temp"), FakeTool("get_humidity")),
            )
        )
        b.build().run("hi").toList()
        val req = llm.recorded.single()
        val toolNames = req.tools.map { it.name }
        assertTrue("get_temp" in toolNames)
        assertTrue("get_humidity" in toolNames)
        assertTrue("skill_weather" in toolNames)
    }

    @Test
    fun `skills(list) registers all in iteration order`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmClient = llm }
        b.skills(
            listOf(
                Skill("a", "d", "BODY_A"),
                Skill("b", "d", "BODY_B"),
            )
        )
        b.build().run("hi").toList()
        val toolNames = llm.recorded.single().tools.map { it.name }
        // Both SkillTools should be present; relative order is registration order.
        val aIdx = toolNames.indexOf("skill_a")
        val bIdx = toolNames.indexOf("skill_b")
        assertTrue(aIdx >= 0 && bIdx >= 0, "expected both in $toolNames")
        assertTrue(aIdx < bIdx, "skill_a should come before skill_b in $toolNames")
    }

    @Test
    fun `skills(registry) uses registration order from the registry`() = runTest {
        val llm = RecordingLlm()
        val registry = SkillRegistry().apply {
            register(Skill("first", "d", "F"))
            register(Skill("second", "d", "S"))
        }
        val b = AgentBuilder().apply { llmClient = llm }
        b.skills(registry)
        b.build().run("hi").toList()
        val toolNames = llm.recorded.single().tools.map { it.name }
        assertTrue(toolNames.indexOf("skill_first") < toolNames.indexOf("skill_second"))
    }

    @Test
    fun `duplicate skill name throws on second registration`() {
        val b = AgentBuilder()
        b.skill(Skill(name = "dup", description = "", body = "B"))
        assertFailsWith<IllegalArgumentException> {
            b.skill(Skill(name = "dup", description = "", body = "B2"))
        }
    }
}
