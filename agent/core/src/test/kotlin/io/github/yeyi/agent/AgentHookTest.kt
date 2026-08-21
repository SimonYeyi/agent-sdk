package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.EchoTool
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.registryOf
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.modality.DefaultModalityAdapter
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

    private class RecordingHook : EmptyAgentHook() {
        val events: MutableList<String> = mutableListOf()
        override suspend fun beforeLlmCall(
            context: AgentContext,
            request: ChatRequest,
        ): ChatRequest {
            events += "beforeLlmCall(${context.currentIteration})"
            return request
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
            synthetic: Boolean,
            durationMs: Long,
        ): ToolExecutionResult {
            events += "afterToolCall(${call.name})"
            return result
        }
        override suspend fun onRunCompleted(context: AgentContext, result: AgentResult) {
            events += "onRunCompleted(iter=${result.iterations})"
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
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = hook
        )
        agent.run(AgentQuery.text("hi")).awaitResult()
        assertEquals(
            listOf(
                "beforeLlmCall(1)",
                "afterLlmResponse(1)",
                "beforeToolCall(echo)",
                "afterToolCall(echo)",
                "beforeLlmCall(2)",
                "afterLlmResponse(2)",
                "onRunCompleted(iter=2)"
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
                    ChatResponseEvent.ToolCallStart(id = "c1", name = "echo"),
                    ChatResponseEvent.ToolCallDelta(id = "c1", name = null, argumentsDelta = "{\"text\":\"x\"}"),
                    ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
                ),
                listOf(
                    ChatResponseEvent.ContentDelta("final"),
                    ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
                )
            )
        )
        val agent = ReActAgent(
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = hook
        )
        agent.runStream(AgentQuery.text("hi")).awaitResult()
        assertEquals(
            listOf(
                "beforeLlmCall(1)",
                "afterLlmResponse(1)",
                "beforeToolCall(echo)",
                "afterToolCall(echo)",
                "beforeLlmCall(2)",
                "afterLlmResponse(2)",
                "onRunCompleted(iter=2)"
            ),
            hook.events
        )
    }

    @Test
    fun `beforeLlmCall return value is sent to provider`() = runTest {
        val hook = object : EmptyAgentHook() {
            override suspend fun beforeLlmCall(
                context: AgentContext,
                request: ChatRequest,
            ): ChatRequest = request.copy(maxTokens = 321)
        }
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(),
            memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = hook,
        )

        agent.run(AgentQuery.text("hi")).awaitResult()

        assertEquals(321, provider.recordedRequests.single().maxTokens)
    }

    @Test
    fun `internal context overflow retry does not duplicate LLM hooks`() = runTest {
        val hook = RecordingHook()
        // 第一次 chat() 抛 context overflow,触发 handleContextOverflow + 内部重试,第二次成功。
        // 关键不变量:重试收敛在 llmCallWithContextOverflowHandle 内部,对 hook 透明;
        // 1 次 ask 仍对应 1 对 beforeLlmCall/afterLlmResponse。
        val provider = object : LlmProvider {
            override val name: String = "overflow-then-ok"
            private var calls = 0
            override suspend fun chat(request: ChatRequest): ChatResponse {
                calls += 1
                if (calls == 1) throw AgentException.ContextOverflow("context length exceeded")
                return ChatResponse(
                    ChatMessage.Assistant(content = "recovered"),
                    finishReason = FinishReason.Stop,
                )
            }
            override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { /* not used */ }
        }
        // 预填历史,让 handleContextOverflow 的 truncateByCoefficient 有足够素材可裁剪
        // (单条非系统消息会触发 IllegalStateException,见 RoundsBoundedMemory.kt:242-244)。
        val memory = InMemoryMemory().apply {
            add(ChatMessage.User(listOf(ContentPart.Text("prev-1"))))
            add(ChatMessage.Assistant("prev-a-1"))
            add(ChatMessage.ToolResult("c0", "echo", listOf(ContentPart.Text("prev-r-1"))))
            add(ChatMessage.User(listOf(ContentPart.Text("prev-2"))))
            add(ChatMessage.Assistant("prev-a-2"))
            add(ChatMessage.ToolResult("c1", "echo", listOf(ContentPart.Text("prev-r-2"))))
        }
        val agent = ReActAgent(
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(),
            memory = memory, modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = hook,
        )
        agent.run(AgentQuery.text("hi")).awaitResult()
        assertEquals(
            listOf(
                "beforeLlmCall(1)",
                "afterLlmResponse(1)",
                "onRunCompleted(iter=1)",
            ),
            hook.events,
        )
    }

    @Test
    fun `exception in hook does not crash agent`() = runTest {
        val throwingHook = object : EmptyAgentHook() {
            override suspend fun beforeLlmCall(
                context: AgentContext,
                request: ChatRequest,
            ): ChatRequest {
                throw RuntimeException("hook fail")
            }
        }
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = ReActAgent(
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = throwingHook
        )
        val result = agent.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("ok", result.message.content)
    }

    @Test
    fun `onRunFailed fires when agent throws`() = runTest {
        val errorHook = object : EmptyAgentHook() {
            val errors: MutableList<Throwable> = mutableListOf()
            override suspend fun onRunFailed(context: AgentContext, cause: Throwable) {
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
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 1, hook = errorHook
        )
        agent.run(AgentQuery.text("hi")).toList()
        assertTrue(errorHook.errors.size == 1)
    }

    @Test
    fun `exception in afterLlmResponse does not crash agent`() = runTest {
        val throwingHook = object : EmptyAgentHook() {
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
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = throwingHook
        )
        val result = agent.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("ok", result.message.content)
    }

    @Test
    fun `exception in afterToolCall does not crash agent`() = runTest {
        val throwingHook = object : EmptyAgentHook() {
            override suspend fun afterToolCall(
                context: AgentContext,
                call: ToolCall,
                result: ToolExecutionResult,
                synthetic: Boolean,
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
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(EchoTool()), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = throwingHook
        )
        val result = agent.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("final", result.message.content)
        assertEquals(1, result.toolCalls.size)
    }

    @Test
    fun `onRunFailed fires for LLM provider throw (not just MaxIterations)`() = runTest {
        val boom = AgentException.LlmError(RuntimeException("llm unavailable"))
        val errorHook = object : EmptyAgentHook() {
            val errors: MutableList<Throwable> = mutableListOf()
            override suspend fun onRunFailed(context: AgentContext, cause: Throwable) {
                errors += cause
            }
        }
        // Custom LLM provider that throws on first chat() call
        val provider = object : LlmProvider {
            override val name: String = "throwing"
            override suspend fun chat(request: ChatRequest): ChatResponse = throw boom
            override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { /* not used */ }
        }
        val agent = ReActAgent(
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = errorHook
        )
        agent.run(AgentQuery.text("hi")).toList()
        assertEquals(1, errorHook.errors.size)
        assertSame(boom, errorHook.errors[0])
    }

    @Test
    fun `CancellationException does not trigger onRunFailed`() = runTest {
        val errorHook = object : EmptyAgentHook() {
            val errors: MutableList<Throwable> = mutableListOf()
            override suspend fun onRunFailed(context: AgentContext, cause: Throwable) {
                errors += cause
            }
        }
        // Custom LLM provider that throws CancellationException
        val provider = object : LlmProvider {
            override val name: String = "cancelling"
            override suspend fun chat(request: ChatRequest): ChatResponse =
                throw kotlinx.coroutines.CancellationException("cancelled")
            override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow { /* not used */ }
        }
        val agent = ReActAgent(
            persona = Persona(""), llmProvider = provider, toolRegistry = registryOf(), memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = errorHook
        )
        try { agent.run(AgentQuery.text("hi")).toList() } catch (t: Throwable) {
            assertTrue(t is kotlinx.coroutines.CancellationException)
        }
        // onRunFailed MUST NOT be called for cancellation
        assertEquals(0, errorHook.errors.size)
    }

    @Test
    fun `beforeToolCall returning non-null short-circuits tool execution but emits tool events`() = runTest {
        val shortCircuit = ToolExecutionResult.success("synthetic-from-hook")
        val hook = object : EmptyAgentHook() {
            override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? {
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
            memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = hook
        )
        val events2 = agent.run(AgentQuery.text("hi")).toList()
        // Short-circuited call still emits ToolCallStart/ToolCallEnd for event stream integrity.
        assertTrue(events2.any { it is AgentEvent.ToolCallStart }, "short-circuited call must emit ToolCallStart")
        assertTrue(events2.any { it is AgentEvent.ToolCallEnd }, "short-circuited call must emit ToolCallEnd")
    }

    @Test
    fun `beforeToolCall short-circuit still records the synthetic result into AgentResult`() = runTest {
        val hook = object : EmptyAgentHook() {
            override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? =
                ToolExecutionResult.success("synthetic-from-hook")
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
            memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = hook
        )
        val result = agent.run(AgentQuery.text("hi")).awaitResult()
        // The synthetic result MUST land in toolCalls (so AgentResult consumers see the call)
        // even though no events were emitted.
        assertEquals(1, result.toolCalls.size)
        assertEquals("synthetic-from-hook", result.toolCalls[0].result.parts.text)
    }

    @Test
    fun `beforeToolCall returning isError=true feeds synthetic error into memory`() = runTest {
        val hook = object : EmptyAgentHook() {
            override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? =
                ToolExecutionResult.error("blocked by hook")
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
            memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = hook
        )
        val result = agent.run(AgentQuery.text("hi")).awaitResult()
        // The agent still completes (no throw). The synthetic error result is what the LLM saw.
        assertEquals("final", result.message.content)
        assertEquals(1, result.toolCalls.size)
        assertEquals("blocked by hook", result.toolCalls[0].result.parts.text)
        assertTrue(result.toolCalls[0].result.isError)
    }

    @Test
    fun `afterToolCall return value overrides raw result`() = runTest {
        val rewritten = ToolExecutionResult.success("rewritten-by-hook")
        val hook = object : EmptyAgentHook() {
            override suspend fun afterToolCall(
                context: AgentContext,
                call: ToolCall,
                result: ToolExecutionResult,
                synthetic: Boolean,
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
            memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = hook
        )
        val result = agent.run(AgentQuery.text("hi")).awaitResult()
        assertEquals(1, result.toolCalls.size)
        // EchoTool normally returns JsonObject text; hook rewrote it.
        assertEquals("rewritten-by-hook", result.toolCalls[0].result.parts.text)
    }

    @Test
    fun `exception in beforeToolCall is swallowed and tool runs normally`() = runTest {
        val hook = object : EmptyAgentHook() {
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
            memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = hook
        )
        val result = agent.run(AgentQuery.text("hi")).awaitResult()
        // exception in beforeToolCall 鈫?tool runs as if no hook short-circuited
        assertEquals("final", result.message.content)
        assertEquals(1, result.toolCalls.size)
    }

    @Test
    fun `metadata is shared between hooks`() = runTest {
        var capturedMetadata: Map<String, String>? = null
        val hook = object : EmptyAgentHook() {
            override suspend fun beforeLlmCall(
                context: AgentContext,
                request: ChatRequest,
            ): ChatRequest {
                context.metadata["key"] = "value"
                return request
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
            memory = InMemoryMemory(), modalityAdapter = DefaultModalityAdapter(), maxRounds = 20, maxIterations = 5, hook = hook
        )
        agent.run(AgentQuery.text("hi")).awaitResult()
        assertEquals("value", capturedMetadata?.get("key"))
    }
}
