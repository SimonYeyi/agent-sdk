package io.github.yeyi.agent.app.demo.subagents

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.app.demo.tools.getWeatherTool
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.skill.SkillContext
import io.github.yeyi.agent.subagent.SubagentContext
import io.github.yeyi.agent.subagent.SubagentTask
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WeatherExpertSubagentTest {

    private fun stubAgentContext(llm: LlmProvider): AgentContext = AgentContext(
        persona = Persona("main"),
        maxIterations = 100,
        currentIteration = 1,
        memory = InMemoryMemory(),
        llmProvider = llm,
        tools = emptyList(),
        maxRounds = 20,
    )

    private fun chatResponse(content: String): ChatResponse =
        ChatResponse(ChatMessage.Assistant(content = content), finishReason = FinishReason.Stop)

    @Test
    fun `subagent exposes name and description`() {
        val sub = WeatherExpertSubagent()
        assertEquals("weather", sub.name)
        assertTrue(
            sub.description.contains("天气"),
            "description should mention 天气, got: ${sub.description}",
        )
    }

    @Test
    fun `loadInstructions returns non-empty multi-section markdown`() {
        val sub = WeatherExpertSubagent()
        val instructions = sub.load()
        assertTrue(instructions.isNotBlank(), "instructions should not be blank")
        assertTrue(instructions.contains("get_weather"), "instructions should reference get_weather")
        assertTrue(
            instructions.contains("## "),
            "instructions should have section structure (## headers)",
        )
    }

    @Test
    fun `activate forwards task to LLM as user message and returns content`() = runTest {
        val llm = FakeLlmProvider(
            nonStreamResponses = listOf(chatResponse("珠海今天天气晴,温度34°C,湿度35%,风速12km/h,天气炎热,不适宜出行!")),
        )
        val sub = WeatherExpertSubagent()
        val ctx = SubagentContext(stubAgentContext(llm))

        val result = sub.activate(SubagentTask("北京今天天气如何"), ctx)

        assertEquals(
            "珠海今天天气晴,温度34°C,湿度35%,风速12km/h,天气炎热,不适宜出行!",
            result,
        )
        assertEquals(1, llm.recordedRequests.size, "subagent must invoke LLM exactly once")
        val userMsgs = llm.recordedRequests.single().messages
            .filterIsInstance<ChatMessage.User>()
            .map { it.content }
        assertTrue(
            userMsgs.contains("北京今天天气如何"),
            "task must be forwarded as user message; got $userMsgs",
        )
    }

    @Test
    fun `activate passes only GetWeatherTool to sub-LLM`() = runTest {
        val llm = FakeLlmProvider(nonStreamResponses = listOf(chatResponse("ok")))
        val sub = WeatherExpertSubagent()
        val ctx = SubagentContext(stubAgentContext(llm))

        sub.activate(SubagentTask("x"), ctx)

        val toolNames = llm.recordedRequests.single().tools.map { it.name }.toSet()
        assertEquals(
            setOf("get_weather"),
            toolNames,
            "subagent must expose only weather tool; got $toolNames",
        )
    }

    @Test
    fun `subagent tools is non-null explicit list containing only weather tool`() {
        val sub = WeatherExpertSubagent()
        val tools = assertNotNull(sub.tools, "subagent should declare explicit tools")
        assertEquals(1, tools.size, "subagent should declare exactly 1 tool")
        assertTrue(tools.any { it.name == "get_weather" }, "tools should include get_weather tool")
    }
}
