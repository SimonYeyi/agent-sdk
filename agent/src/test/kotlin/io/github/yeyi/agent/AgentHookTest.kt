package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.EchoTool
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.registryOf
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.ReadOnlyMemory
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
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
        override suspend fun beforeLlmCall(context: AgentContext) {
            events += "beforeLlmCall(${context.currentIteration})"
        }
        override suspend fun afterLlmResponse(context: AgentContext, response: ChatResponse) {
            events += "afterLlmResponse(${context.currentIteration})"
        }
        override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? {
            events += "beforeToolCall(${call.name})"
            return null
        }
        override suspend fun afterToolCall(
            context: AgentContext,
            call: ToolCall,
            result: ToolExecutionResult,
            durationMs: Long,
        ): ToolExecutionResult {
            events += "afterToolCall(${call.name})"
            return result
        }
        override suspend fun onRunFinished(context: AgentContext, result: AgentResult) {
            events += "onRunFinished(iter=${result.iterations})"
        }
    }

    @Test
    fun `hook receives events in correct order`() = runTest {
        val hook = RecordingHook()
        val provider = FakeLlmProvider(
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
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5, hook = hook
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
        val provider = FakeLlmProvider(
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
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5, hook = hook
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
            override suspend fun beforeLlmCall(context: AgentContext) {
                throw RuntimeException("hook fail")
            }
        }
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5, hook = throwingHook
        )
        val result = agent.run("hi").awaitResult()
        assertEquals("ok", result.message.content)
    }

    @Test
    fun `onError fires when agent throws`() = runTest {
        val errorHook = object : AgentHook {
            val errors: MutableList<AgentException> = mutableListOf()
            override suspend fun onError(context: AgentContext, cause: AgentException) {
                errors += cause
            }
        }
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(toolCalls = listOf(
                        ToolCall("c", "echo", JsonObject(mapOf("text" to JsonPrimitive("x"))))
                    )),
                    finishReason = FinishReason.ToolCalls
                )
            )
        )
        // maxIterations=1,浼氱珛鍒?emit Failed(MaxIterations)
        val agent = ReActAgent(
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 1, hook = errorHook
        )
        agent.run("hi").toList()
        assertTrue(errorHook.errors.size == 1)
    }

    @Test
    fun `exception in afterLlmResponse does not crash agent`() = runTest {
        val throwingHook = object : AgentHook {
            override suspend fun afterLlmResponse(context: AgentContext, response: ChatResponse) {
                throw RuntimeException("afterLlm fail")
            }
        }
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5, hook = throwingHook
        )
        val result = agent.run("hi").awaitResult()
        assertEquals("ok", result.message.content)
    }

    @Test
    fun `exception in afterToolCall does not crash agent`() = runTest {
        val throwingHook = object : AgentHook {
            override suspend fun afterToolCall(
                context: AgentContext,
                call: ToolCall,
                result: ToolExecutionResult,
                durationMs: Long,
            ): ToolExecutionResult {
                throw RuntimeException("afterTool fail")
            }
        }
        val provider = FakeLlmProvider(
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
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5, hook = throwingHook
        )
        val result = agent.run("hi").awaitResult()
        assertEquals("final", result.message.content)
        assertEquals(1, result.toolCalls.size)
    }

    @Test
    fun `onError fires for LLM provider throw (not just MaxIterations)`() = runTest {
        val boom = AgentException.LlmError(RuntimeException("llm unavailable"))
        val errorHook = object : AgentHook {
            val errors: MutableList<AgentException> = mutableListOf()
            override suspend fun onError(context: AgentContext, cause: AgentException) {
                errors += cause
            }
        }
        // Custom LLM provider that throws on first chat() call
        val provider = object : LlmProvider {
            override val name: String = "throwing"
            override suspend fun chat(request: ChatRequest): ChatResponse = throw boom
            override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flow { /* not used */ }
        }
        val agent = ReActAgent(
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5, hook = errorHook
        )
        agent.run("hi").toList()
        assertEquals(1, errorHook.errors.size)
        assertSame(boom, errorHook.errors[0])
    }

    @Test
    fun `CancellationException does not trigger onError`() = runTest {
        val errorHook = object : AgentHook {
            val errors: MutableList<AgentException> = mutableListOf()
            override suspend fun onError(context: AgentContext, cause: AgentException) {
                errors += cause
            }
        }
        // Custom LLM provider that throws CancellationException
        val provider = object : LlmProvider {
            override val name: String = "cancelling"
            override suspend fun chat(request: ChatRequest): ChatResponse =
                throw kotlinx.coroutines.CancellationException("cancelled")
            override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flow { /* not used */ }
        }
        val agent = ReActAgent(
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5, hook = errorHook
        )
        try { agent.run("hi").toList() } catch (t: Throwable) {
            assertTrue(t is kotlinx.coroutines.CancellationException)
        }
        // onError MUST NOT be called for cancellation
        assertEquals(0, errorHook.errors.size)
    }

    @Test
    fun `beforeToolCall returning non-null short-circuits tool execution and emits no tool events`() = runTest {
        val events: MutableList<String> = mutableListOf()
        val shortCircuit = ToolExecutionResult("synthetic-from-hook", isError = false)
        val hook = object : AgentHook {
            override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? {
                events += "beforeToolCall(${call.name})"
                return shortCircuit
            }
        }
        val provider = FakeLlmProvider(
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
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()),
            memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5, hook = hook
        )
        val events2 = agent.run("hi").toList()
        // EchoTool was registered but never invoked: hook short-circuited it.
        // No ToolCallStarted / ToolCallFinished must be emitted (the tool was never called).
        assertTrue(events2.none { it is AgentEvent.ToolCallStart }, "short-circuited call must not emit ToolCallStarted")
        assertTrue(events2.none { it is AgentEvent.ToolCallEnd }, "short-circuited call must not emit ToolCallFinished")
    }

    @Test
    fun `beforeToolCall short-circuit still records the synthetic result into AgentResult`() = runTest {
        val hook = object : AgentHook {
            override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? =
                ToolExecutionResult("synthetic-from-hook", isError = false)
        }
        val provider = FakeLlmProvider(
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
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()),
            memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5, hook = hook
        )
        val result = agent.run("hi").awaitResult()
        // The synthetic result MUST land in toolCalls (so AgentResult consumers see the call)
        // even though no events were emitted.
        assertEquals(1, result.toolCalls.size)
        assertEquals("synthetic-from-hook", result.toolCalls[0].result.content)
    }

    @Test
    fun `beforeToolCall returning isError=true feeds synthetic error into memory`() = runTest {
        val hook = object : AgentHook {
            override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? =
                ToolExecutionResult("blocked by hook", isError = true)
        }
        val provider = FakeLlmProvider(
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
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()),
            memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5, hook = hook
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
                context: AgentContext,
                call: ToolCall,
                result: ToolExecutionResult,
                durationMs: Long,
            ): ToolExecutionResult = rewritten
        }
        val provider = FakeLlmProvider(
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
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()),
            memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5, hook = hook
        )
        val result = agent.run("hi").awaitResult()
        assertEquals(1, result.toolCalls.size)
        // EchoTool normally returns JsonObject text; hook rewrote it.
        assertEquals("rewritten-by-hook", result.toolCalls[0].result.content)
    }

    @Test
    fun `exception in beforeToolCall is swallowed and tool runs normally`() = runTest {
        val hook = object : AgentHook {
            override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? {
                throw RuntimeException("oops")
            }
        }
        val provider = FakeLlmProvider(
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
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()),
            memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5, hook = hook
        )
        val result = agent.run("hi").awaitResult()
        // exception in beforeToolCall 鈫?tool runs as if no hook short-circuited
        assertEquals("final", result.message.content)
        assertEquals(1, result.toolCalls.size)
    }

    @Test
    fun `metadata is shared between hooks`() = runTest {
        var capturedMetadata: Map<String, String>? = null
        val hook = object : AgentHook {
            override suspend fun beforeLlmCall(context: AgentContext) {
                context.metadata["key"] = "value"
            }
            override suspend fun afterLlmResponse(context: AgentContext, response: ChatResponse) {
                capturedMetadata = context.metadata
            }
        }
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "final"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(),
            memory = InMemoryMemory(), maxRounds = 20, maxIterations = 5, hook = hook
        )
        agent.run("hi").awaitResult()
        assertEquals("value", capturedMetadata?.get("key"))
    }
}