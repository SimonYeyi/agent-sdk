package io.github.yeyi.agent.demo.agent.demo.tools

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllToolsTest {

    private fun stubContext(): ToolContext = ToolContext(
        toolCallId = "test-call-id",
        agentContext = AgentContext(
            persona = Persona(""),
            maxIterations = 1,
            currentIteration = 1,
            memory = InMemoryMemory(),
            llmProvider = FakeLlmProvider(),
            tools = emptyList(),
            maxRounds = 20,
        )
    )

    @Test
    fun `GetCurrentTimeTool returns ISO-8601 string`() = runTest {
        val tool = GetCurrentTimeTool()
        val result = tool.execute(buildJsonObject {}, stubContext())
        assertTrue(result.content.startsWith("Current UTC time: "), "got: ${result.content}")
        assertFalse(result.isError)
    }

    @Test
    fun `CalculatorTool computes simple expression`() = runTest {
        val tool = CalculatorTool()
        val arguments = buildJsonObject { put("expression", JsonPrimitive("(3+5)*7")) }
        val result = tool.execute(arguments, stubContext())
        assertEquals("(3+5)*7 = 56", result.content)
        assertFalse(result.isError)
    }

    @Test
    fun `CalculatorTool returns error on invalid input`() = runTest {
        val tool = CalculatorTool()
        val arguments = buildJsonObject { put("expression", JsonPrimitive("abc")) }
        val result = tool.execute(arguments, stubContext())
        assertTrue(result.isError, "expected error result, got: ${result.content}")
    }

    @Test
    fun `WebSearchMockTool returns mock result with delay`() = runTest {
        val tool = WebSearchTool()
        val arguments = buildJsonObject { put("query", JsonPrimitive("kotlin coroutines")) }
        val result = tool.execute(arguments, stubContext())
        assertTrue(result.content.contains("kotlin coroutines"), "got: ${result.content}")
        assertFalse(result.isError)
    }
}
