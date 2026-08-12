package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReActAgentMultimodalTest {

    private fun makeAgent(provider: FakeLlmProvider): Agent = ReActAgent(
        persona = Persona("you are helpful"),
        llmProvider = provider,
        toolRegistry = ToolRegistry(),
        memory = InMemoryMemory(),
        maxRounds = 20,
        maxIterations = 5
    )

    @Test
    fun `run with AgentQuery text equivalent to old String run`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    message = ChatMessage.Assistant(content = "hello back"),
                    finishReason = FinishReason.Stop
                )
            )
        )
        val agent = makeAgent(provider)
        val finalEvent = agent.run(AgentQuery.text("hi")).toList()
            .filterIsInstance<AgentEvent.Final>().first()
        assertEquals("hello back", finalEvent.result.message.content)
    }

    @Test
    fun `Initial event carries AgentQuery`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    message = ChatMessage.Assistant(content = "ok"),
                    finishReason = FinishReason.Stop
                )
            )
        )
        val agent = makeAgent(provider)
        val events = agent.run(AgentQuery.text("hi")).toList()
        val initial = events.filterIsInstance<AgentEvent.Initial>().first()
        assertEquals(AgentQuery.text("hi"), initial.query)
    }
}
