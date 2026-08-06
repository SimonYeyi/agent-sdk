package io.github.yeyi.agent.tool

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolParametersTest {
    @Test
    fun `Empty is a singleton object`() {
        val a: ToolParameters = ToolParameters.Empty
        val b: ToolParameters = ToolParameters.Empty
        assertTrue(a === b)
    }

    @Test
    fun `JsonSchema holds schema string`() {
        val s = ToolParameters.JsonSchema("{\"type\":\"object\"}")
        assertEquals("{\"type\":\"object\"}", s.schema)
    }

    @Test
    fun `ToolExecutionResult defaults isError false`() {
        val r = ToolExecutionResult("done")
        assertEquals("done", r.content)
        assertEquals(false, r.isError)
    }

    @Test
    fun `ToolContext exposes toolCallId and AgentContext metadata defaults to empty`() {
        val context = ToolContext(
            toolCallId = "call-123",
            agentContext = AgentContext(
                persona = Persona(""),
                maxIterations = 1,
                currentIteration = 1,
                memory = InMemoryMemory(),
                llmProvider = FakeLlmProvider(),
                tools = emptyList(),
                maxRounds = 20,
            ),
        )
        assertEquals("call-123", context.toolCallId)
        assertTrue(context.agentContext.metadata.isEmpty())
    }
}
