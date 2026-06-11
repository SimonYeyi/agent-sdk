package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.EchoTool
import io.github.yeyi.agent.fakes.FakeLlmClient
import io.github.yeyi.agent.fakes.registryOf
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmClient
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        override suspend fun beforeToolCall(call: ToolCall): ToolExecutionResult? {
            events += "beforeToolCall(${call.name})"
            return null
        }
        override suspend fun afterToolCall(
            call: ToolCall,
            result: ToolExecutionResult,
            durationMs: Long,
        ): ToolExecutionResult {
            events += "afterToolCall(${call.name})"
            return result
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
            systemPrompt = "", llmClient = client, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), maxIterations = 5, hook = hook
        )
        agent.run("hi").awaitResult()
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
    fun `runStream also fires all hooks in order`() = runTest {
        val hook = RecordingHook()
        val client = FakeLlmClient(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ToolCallStart(id = "c1", name = "echo"),
                    StreamEvent.ToolCallDelta(id = "c1", name = null, argumentsDelta = "{\"text\":\"x\"}"),
                    StreamEvent.Done(usage = null, finishReason = FinishReason.Stop)
                ),
                listOf(
                    StreamEvent.ContentDelta("final"),
                    StreamEvent.Done(usage = null, finishReason = FinishReason.Stop)
                )
            )
        )
        val agent = ReActAgent(
            systemPrompt = "", llmClient = client, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), maxIterations = 5, hook = hook
        )
        agent.runStream("hi").awaitResult()
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
            systemPrompt = "", llmClient = client, toolRegistry = registryOf(), memory = InMemoryMemory(), maxIterations = 5, hook = throwingHook
        )
        val result = agent.run("hi").awaitResult()
        assertEquals("ok", result.message.content)
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
        // maxIterations=1,会立刻 emit Failed(MaxIterations)
        val agent = ReActAgent(
            systemPrompt = "", llmClient = client, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), maxIterations = 1, hook = errorHook
        )
        agent.run("hi").toList()
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
            systemPrompt = "", llmClient = client, toolRegistry = registryOf(), memory = InMemoryMemory(), maxIterations = 5, hook = throwingHook
        )
        val result = agent.run("hi").awaitResult()
        assertEquals("ok", result.message.content)
    }

    @Test
    fun `exception in afterToolCall does not crash agent`() = runTest {
        val throwingHook = object : AgentHook {
            override suspend fun afterToolCall(
                call: ToolCall,
                result: ToolExecutionResult,
                durationMs: Long,
            ): ToolExecutionResult {
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
            systemPrompt = "", llmClient = client, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), maxIterations = 5, hook = throwingHook
        )
        val result = agent.run("hi").awaitResult()
        assertEquals("final", result.message.content)
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
            systemPrompt = "", llmClient = client, toolRegistry = registryOf(), memory = InMemoryMemory(), maxIterations = 5, hook = errorHook
        )
        agent.run("hi").toList()
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
            systemPrompt = "", llmClient = client, toolRegistry = registryOf(), memory = InMemoryMemory(), maxIterations = 5, hook = errorHook
        )
        try { agent.run("hi").toList() } catch (t: Throwable) {
            assertTrue(t is kotlinx.coroutines.CancellationException)
        }
        // onError MUST NOT be called for cancellation
        assertEquals(0, errorHook.errors.size)
    }

    @Test
    fun `beforeToolCall returning non-null short-circuits tool execution`() = runTest {
        val events: MutableList<String> = mutableListOf()
        val shortCircuit = ToolExecutionResult("synthetic-from-hook", isError = false)
        val hook = object : AgentHook {
            override suspend fun beforeToolCall(call: ToolCall): ToolExecutionResult? {
                events += "beforeToolCall(${call.name})"
                return shortCircuit
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
            systemPrompt = "", llmClient = client, toolRegistry = registryOf(EchoTool()),
            memory = InMemoryMemory(), maxIterations = 5, hook = hook
        )
        val events2 = agent.run("hi").toList()
        // EchoTool was registered but never invoked: hook short-circuited it.
        // Verify by inspecting emitted events: ToolCallStarted must NOT appear, ToolCallFinished MUST.
        assertTrue(events2.none { it is AgentEvent.ToolCallStarted }, "short-circuited call must not emit ToolCallStarted")
        val finished = events2.filterIsInstance<AgentEvent.ToolCallFinished>()
        assertEquals(1, finished.size)
        assertEquals("synthetic-from-hook", finished[0].result.content)
        assertFalse(finished[0].result.isError)
    }

    @Test
    fun `beforeToolCall returning isError=true feeds synthetic error into memory`() = runTest {
        val hook = object : AgentHook {
            override suspend fun beforeToolCall(call: ToolCall): ToolExecutionResult? =
                ToolExecutionResult("blocked by hook", isError = true)
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
            systemPrompt = "", llmClient = client, toolRegistry = registryOf(EchoTool()),
            memory = InMemoryMemory(), maxIterations = 5, hook = hook
        )
        val result = agent.run("hi").awaitResult()
        // The agent still completes (no throw). The synthetic error result is what the LLM saw.
        assertEquals("final", result.message.content)
        assertEquals(1, result.toolCalls.size)
        assertEquals("blocked by hook", result.toolCalls[0].result.content)
        assertTrue(result.toolCalls[0].result.isError)
    }

    @Test
    fun `afterToolCall return value overrides raw result`() = runTest {
        val rewritten = ToolExecutionResult("rewritten-by-hook", isError = false)
        val hook = object : AgentHook {
            override suspend fun afterToolCall(
                call: ToolCall,
                result: ToolExecutionResult,
                durationMs: Long,
            ): ToolExecutionResult = rewritten
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
            systemPrompt = "", llmClient = client, toolRegistry = registryOf(EchoTool()),
            memory = InMemoryMemory(), maxIterations = 5, hook = hook
        )
        val result = agent.run("hi").awaitResult()
        assertEquals(1, result.toolCalls.size)
        // EchoTool normally returns JsonObject text; hook rewrote it.
        assertEquals("rewritten-by-hook", result.toolCalls[0].result.content)
    }

    @Test
    fun `exception in beforeToolCall is swallowed and tool runs normally`() = runTest {
        val hook = object : AgentHook {
            override suspend fun beforeToolCall(call: ToolCall): ToolExecutionResult? {
                throw RuntimeException("oops")
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
            systemPrompt = "", llmClient = client, toolRegistry = registryOf(EchoTool()),
            memory = InMemoryMemory(), maxIterations = 5, hook = hook
        )
        val result = agent.run("hi").awaitResult()
        // exception in beforeToolCall → tool runs as if no hook short-circuited
        assertEquals("final", result.message.content)
        assertEquals(1, result.toolCalls.size)
    }
}
