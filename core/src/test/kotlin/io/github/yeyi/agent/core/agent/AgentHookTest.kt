package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.agent.fakes.EchoTool
import io.github.yeyi.agent.core.agent.fakes.FakeLlmClient
import io.github.yeyi.agent.core.llm.ChatMessage
import io.github.yeyi.agent.core.llm.ChatRequest
import io.github.yeyi.agent.core.llm.ChatResponse
import io.github.yeyi.agent.core.llm.FinishReason
import io.github.yeyi.agent.core.llm.LlmClient
import io.github.yeyi.agent.core.llm.StreamEvent
import io.github.yeyi.agent.core.llm.ToolCall
import io.github.yeyi.agent.core.memory.InMemoryMemory
import io.github.yeyi.agent.core.tool.ToolExecutionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
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

    @Test
    fun `exception in afterLlmResponse does not crash agent`() = runTest {
        val throwingHook = object : AgentHook {
            override suspend fun afterLlmResponse(iteration: Int, response: ChatResponse) {
                throw RuntimeException("afterLlm fail")
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
    fun `exception in afterToolCall does not crash agent`() = runTest {
        val throwingHook = object : AgentHook {
            override suspend fun afterToolCall(call: ToolCall, result: ToolExecutionResult, durationMs: Long) {
                throw RuntimeException("afterTool fail")
            }
        }
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
            AgentConfig("", client, listOf(EchoTool()), { InMemoryMemory() }, 5, hooks = listOf(throwingHook))
        )
        val result = agent.run("hi", InMemoryMemory())
        assertEquals("final", result.finalMessage.content)
        assertEquals(1, result.toolCalls.size)
    }

    @Test
    fun `onError fires for LLM client throw (not just MaxIterations)`() = runTest {
        val boom = RuntimeException("llm unavailable")
        val errorHook = object : AgentHook {
            val errors: MutableList<Throwable> = mutableListOf()
            override suspend fun onError(iteration: Int, cause: Throwable) {
                errors += cause
            }
        }
        // Custom LLM client that throws on first chat() call
        val client = object : LlmClient {
            override val providerName: String = "throwing"
            override suspend fun chat(request: ChatRequest): ChatResponse = throw boom
            override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flow { /* not used */ }
        }
        val agent = ReActAgent(
            AgentConfig("", client, emptyList(), { InMemoryMemory() }, 5, hooks = listOf(errorHook))
        )
        try { agent.run("hi", InMemoryMemory()) } catch (_: Throwable) { /* expected */ }
        assertEquals(1, errorHook.errors.size)
        assertSame(boom, errorHook.errors[0])
    }

    @Test
    fun `CancellationException does not trigger onError`() = runTest {
        val errorHook = object : AgentHook {
            val errors: MutableList<Throwable> = mutableListOf()
            override suspend fun onError(iteration: Int, cause: Throwable) {
                errors += cause
            }
        }
        // Custom LLM client that throws CancellationException
        val client = object : LlmClient {
            override val providerName: String = "cancelling"
            override suspend fun chat(request: ChatRequest): ChatResponse =
                throw kotlinx.coroutines.CancellationException("cancelled")
            override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flow { /* not used */ }
        }
        val agent = ReActAgent(
            AgentConfig("", client, emptyList(), { InMemoryMemory() }, 5, hooks = listOf(errorHook))
        )
        try { agent.run("hi", InMemoryMemory()) } catch (t: Throwable) {
            assertTrue(t is kotlinx.coroutines.CancellationException)
        }
        // onError MUST NOT be called for cancellation
        assertEquals(0, errorHook.errors.size)
    }
}
