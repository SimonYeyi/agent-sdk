package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.agent.fakes.EchoTool
import io.github.yeyi.agent.core.agent.fakes.FakeLlmClient
import io.github.yeyi.agent.core.llm.ChatMessage
import io.github.yeyi.agent.core.llm.ChatResponse
import io.github.yeyi.agent.core.llm.FinishReason
import io.github.yeyi.agent.core.llm.ToolCall
import io.github.yeyi.agent.core.memory.InMemoryMemory
import io.github.yeyi.agent.core.tool.ToolExecutionResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentHookTest {

    private class RecordingHook : AgentHook {
        val events: MutableList<String> = mutableListOf()
        override suspend fun beforeLlmCall(iteration: Int, messages: List<ChatMessage>) {
            events += "beforeLlmCall($iteration)"
        }
        override suspend fun afterLlmResponse(iteration: Int, response: ChatResponse) {
            events += "afterLlmResponse($iteration)"
        }
        override suspend fun beforeToolCall(call: ToolCall) {
            events += "beforeToolCall(${call.name})"
        }
        override suspend fun afterToolCall(call: ToolCall, result: ToolExecutionResult, durationMs: Long) {
            events += "afterToolCall(${call.name})"
        }
        override suspend fun onRunFinished(result: AgentResult) {
            events += "onRunFinished(iter=${result.iterations})"
        }
    }

    @Test
    fun `hook receives events in correct order`() = runTest {
        val hook = RecordingHook()
        val client = FakeLlmClient(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(toolCalls = listOf(
                        ToolCall("c1", "echo", JsonObject(mapOf("text" to JsonPrimitive("x"))))
                    )),
                    finishReason = FinishReason.ToolCalls
                ),
                ChatResponse(ChatMessage.Assistant(content = "final"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(
            AgentConfig("", client, listOf(EchoTool()), { InMemoryMemory() }, 5, hooks = listOf(hook))
        )
        agent.run("hi", InMemoryMemory())
        assertEquals(
            listOf(
                "beforeLlmCall(1)",
                "afterLlmResponse(1)",
                "beforeToolCall(echo)",
                "afterToolCall(echo)",
                "beforeLlmCall(2)",
                "afterLlmResponse(2)",
                "onRunFinished(iter=2)"
            ),
            hook.events
        )
    }

    @Test
    fun `exception in hook does not crash agent`() = runTest {
        val throwingHook = object : AgentHook {
            override suspend fun beforeLlmCall(iteration: Int, messages: List<ChatMessage>) {
                throw RuntimeException("hook fail")
            }
        }
        val client = FakeLlmClient(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(
            AgentConfig("", client, emptyList(), { InMemoryMemory() }, 5, hooks = listOf(throwingHook))
        )
        val result = agent.run("hi", InMemoryMemory())
        assertEquals("ok", result.finalMessage.content)
    }

    @Test
    fun `onError fires when agent throws`() = runTest {
        val errorHook = object : AgentHook {
            val errors: MutableList<Throwable> = mutableListOf()
            override suspend fun onError(iteration: Int, cause: Throwable) {
                errors += cause
            }
        }
        val client = FakeLlmClient(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(toolCalls = listOf(
                        ToolCall("c", "echo", JsonObject(mapOf("text" to JsonPrimitive("x"))))
                    )),
                    finishReason = FinishReason.ToolCalls
                )
            )
        )
        // maxIterations=1,会立刻 throw MaxIterations
        val agent = ReActAgent(
            AgentConfig("", client, listOf(EchoTool()), { InMemoryMemory() }, 1, hooks = listOf(errorHook))
        )
        try { agent.run("hi", InMemoryMemory()) } catch (_: Throwable) { /* expected */ }
        assertTrue(errorHook.errors.size == 1)
    }
}
