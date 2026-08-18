@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BossAgentBuilderTest {

    private class RecordingHook : AgentHook {
        var llmCalls: Int = 0

        override suspend fun beforeMemoryCompress(context: AgentContext, summaries: List<Summary>) = Unit
        override suspend fun afterMemoryCompress(context: AgentContext, summaries: List<Summary>) = Unit
        override suspend fun beforeLlmCall(context: AgentContext, request: ChatRequest): ChatRequest {
            llmCalls++
            return request
        }
        override suspend fun afterLlmResponse(context: AgentContext, response: ChatResponse) = Unit
        override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? = null
        override suspend fun afterToolCall(
            context: AgentContext,
            call: ToolCall,
            result: ToolExecutionResult,
            synthetic: Boolean,
            durationMs: Long,
        ): ToolExecutionResult = result
        override suspend fun onRunCompleted(context: AgentContext, result: AgentResult) = Unit
        override suspend fun onRunFailed(context: AgentContext, cause: Throwable) = Unit
    }

    @Test
    fun `bossAgent DSL builds successfully`() = runTest {
        val boss = bossAgent {
            llmProvider(
                FakeLlmProvider(
                    nonStreamResponses = listOf(
                        ChatResponse(
                            message = ChatMessage.Assistant(content = "test"),
                            finishReason = FinishReason.Stop,
                        )
                    )
                )
            )
            memory(InMemoryMemory(), 20)
            maxIterations(1)
        }

        val events = boss.run(AgentQuery.text("hello")).toList()
        assertTrue(events.isNotEmpty())
        boss.shutdown()
    }

    @Test
    fun `bossAgent hook observes inner agent LLM call`() = runTest {
        val recordingHook = RecordingHook()
        val boss = bossAgent {
            llmProvider(
                FakeLlmProvider(
                    nonStreamResponses = listOf(
                        ChatResponse(
                            message = ChatMessage.Assistant(content = "test"),
                            finishReason = FinishReason.Stop,
                        )
                    )
                )
            )
            memory(InMemoryMemory(), 20)
            hook(recordingHook)
            maxIterations(1)
        }

        boss.run(AgentQuery.text("hello")).toList()

        assertEquals(1, recordingHook.llmCalls)
        boss.shutdown()
    }
}
