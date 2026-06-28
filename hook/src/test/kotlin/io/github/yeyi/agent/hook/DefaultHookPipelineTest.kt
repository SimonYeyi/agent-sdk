package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DefaultHookPipelineTest {

    private fun context(iter: Int = 1) = AgentContext(
        persona = Persona(role = ""),
        maxIterations = 5,
        currentIteration = iter,
        memory = InMemoryMemory(),
        llmProvider = FakeLlmProvider(),
        tools = emptyList<Tool>(),
        maxRounds = 20,
    )

    private val allEvents = setOf(
        BeforeMemoryCompress::class,
        AfterMemoryCompress::class,
        BeforeLlmCall::class,
        AfterLlmResponse::class,
        BeforeToolCall::class,
        AfterToolCall::class,
        OnRunFinished::class,
        OnError::class
    )

    /** Records every lifecycle call via execute() for assertion. Implements [Hook]. */
    private inner class RecordingHook(
        private val hookName: String,
        val subscribedEvents: Set<kotlin.reflect.KClass<out Event>> = allEvents
    ) : Hook {
        override val name: String = hookName
        val recordedEvents: MutableList<String> = mutableListOf()
        var nextSynthetic: String? = null
        var nextRewritten: ToolExecutionResult? = null

        override val events: Set<kotlin.reflect.KClass<out Event>> = subscribedEvents
        override val priority: Int = 100

        override suspend fun execute(event: Event, context: HookContext): Result {
            when (event) {
                is BeforeLlmCall -> {
                    recordedEvents += "${name}:beforeLlmCall(${context.agentContext?.currentIteration})"
                }
                is AfterLlmResponse -> {
                    recordedEvents += "${name}:afterLlmResponse(${context.agentContext?.currentIteration})"
                }
                is BeforeToolCall -> {
                    recordedEvents += "${name}:beforeToolCall(${event.toolCall.name})"
                    return if (nextSynthetic != null) {
                        Result.Halt(nextSynthetic!!)
                    } else {
                        Result.Continue
                    }
                }
                is AfterToolCall -> {
                    recordedEvents += "${name}:afterToolCall(${event.toolCall.name},${event.result.content})"
                    return if (nextRewritten != null) {
                        Result.Modify(nextRewritten!!)
                    } else {
                        Result.Continue
                    }
                }
                is OnError -> {
                    recordedEvents += "${name}:onError(${event.error.javaClass.simpleName})"
                }
                is OnRunFinished -> {
                    recordedEvents += "${name}:onRunFinished(iter=${event.result.iterations})"
                }
                is BeforeMemoryCompress -> {
                    recordedEvents += "${name}:beforeMemoryCompress"
                }
                is AfterMemoryCompress -> {
                    recordedEvents += "${name}:afterMemoryCompress"
                }
            }
            return Result.Continue
        }
    }

    private fun emptyResponse() = ChatResponse(
        message = ChatMessage.Assistant(content = ""),
        finishReason = FinishReason.Stop
    )

    private fun toolCall(name: String = "x") = ToolCall(
        id = "c1",
        name = name,
        arguments = JsonObject(mapOf("k" to JsonPrimitive("v")))
    )

    // --- Fan-out: void callbacks ---

    @Test
    fun `lifecycle hooks fan out in registration order`() = runTest {
        val a = RecordingHook("a")
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(a, b))
        val r = emptyResponse()
        composite.beforeLlmCall(context(1))
        composite.afterLlmResponse(context(1), r)
        composite.onError(context(1), AgentException.LlmError(RuntimeException("x")))
        composite.onRunFinished(context(1), AgentResult(r.message, 1, emptyList(), null))
        assertEquals(
            listOf(
                "a:beforeLlmCall(1)",
                "a:afterLlmResponse(1)",
                "a:onError(LlmError)",
                "a:onRunFinished(iter=1)",
                "b:beforeLlmCall(1)",
                "b:afterLlmResponse(1)",
                "b:onError(LlmError)",
                "b:onRunFinished(iter=1)",
            ),
            a.recordedEvents + b.recordedEvents
        )
    }

    // --- Short-circuit: beforeToolCall first Halt wins ---

    @Test
    fun `beforeToolCall returns first synthetic and short-circuits rest`() = runTest {
        val a = RecordingHook("a").apply { nextSynthetic = "from-a" }
        val b = RecordingHook("b").apply { nextSynthetic = "from-b" }
        val c = RecordingHook("c")
        val composite = DefaultHookPipeline(listOf(a, b, c))
        val r = composite.beforeToolCall(context(), toolCall())
        assertEquals("from-a", r!!.content)
        assertEquals(listOf("a:beforeToolCall(x)"), a.recordedEvents)
        assertEquals(emptyList<String>(), b.recordedEvents, "b should NOT be called after a returned Halt")
        assertEquals(emptyList<String>(), c.recordedEvents)
    }

    @Test
    fun `beforeToolCall skips hooks returning Continue and takes the first Halt`() = runTest {
        val a = RecordingHook("a")
        val b = RecordingHook("b")
        val c = RecordingHook("c").apply { nextSynthetic = "from-c" }
        val composite = DefaultHookPipeline(listOf(a, b, c))
        val r = composite.beforeToolCall(context(), toolCall())
        assertEquals("from-c", r!!.content)
        assertEquals(
            listOf("a:beforeToolCall(x)", "b:beforeToolCall(x)", "c:beforeToolCall(x)"),
            a.recordedEvents + b.recordedEvents + c.recordedEvents
        )
    }

    @Test
    fun `beforeToolCall returns null when all hooks return Continue`() = runTest {
        val composite = DefaultHookPipeline(listOf(RecordingHook("a"), RecordingHook("b")))
        assertNull(composite.beforeToolCall(context(), toolCall()))
    }

    // --- Chain: afterToolCall ---

    @Test
    fun `afterToolCall chains each hook sees previous output`() = runTest {
        val a = RecordingHook("a").apply { nextRewritten = ToolExecutionResult("a-out") }
        val b = RecordingHook("b").apply { nextRewritten = ToolExecutionResult("b-out") }
        val c = RecordingHook("c")
        val composite = DefaultHookPipeline(listOf(a, b, c))
        val initial = ToolExecutionResult("raw")
        val final = composite.afterToolCall(context(), toolCall(), initial, 5)
        assertEquals("b-out", final.content, "c didn't rewrite - final is b's output")
        assertEquals(listOf("a:afterToolCall(x,raw)"), a.recordedEvents)
        assertEquals(listOf("b:afterToolCall(x,a-out)"), b.recordedEvents)
        assertEquals(listOf("c:afterToolCall(x,b-out)"), c.recordedEvents)
    }

    @Test
    fun `afterToolCall returns input when no hook rewrites`() = runTest {
        val composite = DefaultHookPipeline(listOf(RecordingHook("a"), RecordingHook("b")))
        val input = ToolExecutionResult("untouched")
        val output = composite.afterToolCall(context(), toolCall(), input, 5)
        assertSame(input, output)
    }

    // --- Exception isolation ---

    @Test
    fun `exception in beforeLlmCall is swallowed and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out Event>> = setOf(BeforeLlmCall::class)
            override suspend fun execute(event: Event, context: HookContext): Result {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(throwing, b))
        composite.beforeLlmCall(context(1))
        assertEquals(listOf("b:beforeLlmCall(1)"), b.recordedEvents)
    }

    @Test
    fun `exception in afterLlmResponse is swallowed and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out Event>> = setOf(AfterLlmResponse::class)
            override suspend fun execute(event: Event, context: HookContext): Result {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(throwing, b))
        composite.afterLlmResponse(context(1), emptyResponse())
        assertEquals(listOf("b:afterLlmResponse(1)"), b.recordedEvents)
    }

    @Test
    fun `exception in onError is swallowed and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out Event>> = setOf(OnError::class)
            override suspend fun execute(event: Event, context: HookContext): Result {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(throwing, b))
        composite.onError(context(1), AgentException.LlmError(RuntimeException("orig")))
        assertEquals(listOf("b:onError(LlmError)"), b.recordedEvents)
    }

    @Test
    fun `exception in onRunFinished is swallowed and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out Event>> = setOf(OnRunFinished::class)
            override suspend fun execute(event: Event, context: HookContext): Result {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(throwing, b))
        val r = emptyResponse()
        composite.onRunFinished(context(1), AgentResult(r.message, 1, emptyList(), null))
        assertEquals(listOf("b:onRunFinished(iter=1)"), b.recordedEvents)
    }

    @Test
    fun `exception in beforeToolCall is treated as Continue and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out Event>> = setOf(BeforeToolCall::class)
            override suspend fun execute(event: Event, context: HookContext): Result {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b").apply { nextSynthetic = "from-b" }
        val composite = DefaultHookPipeline(listOf(throwing, b))
        val r = composite.beforeToolCall(context(), toolCall())
        assertEquals("from-b", r!!.content, "throwing hook's exception should be swallowed, b wins")
    }

    @Test
    fun `exception in afterToolCall keeps previous value and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out Event>> = setOf(AfterToolCall::class)
            override suspend fun execute(event: Event, context: HookContext): Result {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b").apply { nextRewritten = ToolExecutionResult("b-out") }
        val composite = DefaultHookPipeline(listOf(throwing, b))
        val input = ToolExecutionResult("raw")
        val out = composite.afterToolCall(context(), toolCall(), input, 5)
        assertEquals("b-out", out.content)
    }

    @Test
    fun `CancellationException in beforeLlmCall propagates and stops fan-out`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out Event>> = setOf(BeforeLlmCall::class)
            override val priority: Int = Int.MAX_VALUE
            override suspend fun execute(event: Event, context: HookContext): Result {
                throw CancellationException("cancelled")
            }
        }
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(throwing, b))
        var caught: Throwable? = null
        try {
            composite.beforeLlmCall(context(1))
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(caught is CancellationException)
        assertEquals(emptyList<String>(), b.recordedEvents, "cancellation must stop fan-out")
    }

    @Test
    fun `CancellationException in beforeToolCall propagates immediately`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out Event>> = setOf(BeforeToolCall::class)
            override val priority: Int = Int.MAX_VALUE
            override suspend fun execute(event: Event, context: HookContext): Result {
                throw CancellationException("cancelled")
            }
        }
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(throwing, b))
        var caught: Throwable? = null
        try {
            composite.beforeToolCall(context(), toolCall())
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(caught is CancellationException)
        assertEquals(emptyList<String>(), b.recordedEvents)
    }

    @Test
    fun `CancellationException in afterToolCall propagates immediately`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out Event>> = setOf(AfterToolCall::class)
            override suspend fun execute(event: Event, context: HookContext): Result {
                throw CancellationException("cancelled")
            }
        }
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(throwing, b))
        var caught: Throwable? = null
        try {
            composite.afterToolCall(context(), toolCall(), ToolExecutionResult("x"), 5)
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(caught is CancellationException)
    }

    // --- Hook ordering preserved ---

    @Test
    fun `register order is preserved across 5 hooks`() = runTest {
        val hooks = (0 until 5).map { RecordingHook("h$it") }
        val composite = DefaultHookPipeline(hooks.toList())
        composite.beforeLlmCall(context(1))
        for ((i, h) in hooks.withIndex()) {
            assertEquals(listOf("h$i:beforeLlmCall(1)"), h.recordedEvents, "hook $i should be called in order")
        }
    }

    // --- Memory compress hooks ---

    @Test
    fun `memory compress hooks fan out to all subscribed hooks`() = runTest {
        val a = RecordingHook("a")
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(a, b))
        val summaries = listOf(Summary("sum1"), Summary("sum2"))
        composite.beforeMemoryCompress(context(1), summaries)
        composite.afterMemoryCompress(context(1), summaries)
        assertEquals(
            listOf(
                "a:beforeMemoryCompress",
                "a:afterMemoryCompress",
                "b:beforeMemoryCompress",
                "b:afterMemoryCompress",
            ),
            a.recordedEvents + b.recordedEvents
        )
    }
}