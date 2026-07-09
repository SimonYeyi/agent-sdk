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
        AgentHookEvent.BeforeMemoryCompress::class,
        AgentHookEvent.AfterMemoryCompress::class,
        AgentHookEvent.BeforeLlmCall::class,
        AgentHookEvent.AfterLlmResponse::class,
        AgentHookEvent.BeforeToolCall::class,
        AgentHookEvent.AfterToolCall::class,
        AgentHookEvent.RunCompleted::class,
        AgentHookEvent.RunFailed::class
    )

    /** Records every lifecycle call via execute() for assertion. Implements [Hook]. */
    private inner class RecordingHook(
        private val hookName: String,
        val subscribedEvents: Set<kotlin.reflect.KClass<out HookEvent>> = allEvents,
        var priorityOverride: Int = 100
    ) : Hook {
        override val name: String = hookName
        val recordedEvents: MutableList<String> = mutableListOf()
        var nextSynthetic: String? = null
        var nextRewritten: ToolExecutionResult? = null

        override val events: Set<kotlin.reflect.KClass<out HookEvent>> = subscribedEvents
        override val priority: Int get() = priorityOverride

        override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
            when (event) {
                is AgentHookEvent.BeforeLlmCall -> {
                    recordedEvents += "${name}:beforeLlmCall(${context.agentContext?.currentIteration})"
                }
                is AgentHookEvent.AfterLlmResponse -> {
                    recordedEvents += "${name}:afterLlmResponse(${context.agentContext?.currentIteration})"
                }
                is AgentHookEvent.BeforeToolCall -> {
                    recordedEvents += "${name}:beforeToolCall(${event.toolCall.name})"
                    return if (nextSynthetic != null) {
                        HookResult.Refuse(nextSynthetic!!)
                    } else {
                        HookResult.Continue
                    }
                }
                is AgentHookEvent.AfterToolCall -> {
                    recordedEvents += "${name}:afterToolCall(${event.toolCall.name},${event.result.content})"
                    return if (nextRewritten != null) {
                        HookResult.Modify(nextRewritten!!)
                    } else {
                        HookResult.Continue
                    }
                }
                is AgentHookEvent.RunFailed -> {
                    recordedEvents += "${name}:onRunFailed(${event.error.javaClass.simpleName})"
                }
                is AgentHookEvent.RunCompleted -> {
                    recordedEvents += "${name}:onRunCompleted(iter=${event.result.iterations})"
                }
                is AgentHookEvent.BeforeMemoryCompress -> {
                    recordedEvents += "${name}:beforeMemoryCompress"
                }
                is AgentHookEvent.AfterMemoryCompress -> {
                    recordedEvents += "${name}:afterMemoryCompress"
                }
                else -> {
                    // ignore other events
                }
            }
            return HookResult.Continue
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
        composite.onRunFailed(context(1), AgentException.LlmError(RuntimeException("x")))
        composite.onRunCompleted(context(1), AgentResult(r.message, 1, emptyList(), null))
        assertEquals(
            listOf(
                "a:beforeLlmCall(1)",
                "a:afterLlmResponse(1)",
                "a:onRunFailed(LlmError)",
                "a:onRunCompleted(iter=1)",
                "b:beforeLlmCall(1)",
                "b:afterLlmResponse(1)",
                "b:onRunFailed(LlmError)",
                "b:onRunCompleted(iter=1)",
            ),
            a.recordedEvents + b.recordedEvents
        )
    }

    // --- Vote-mode: beforeToolCall Refuse is aggregated, not short-circuit ---

    @Test
    fun `beforeToolCall calls all hooks and aggregates Refuses without short-circuiting`() = runTest {
        val a = RecordingHook("a").apply { nextSynthetic = "from-a" }
        val b = RecordingHook("b").apply { nextSynthetic = "from-b" }
        val c = RecordingHook("c")
        val composite = DefaultHookPipeline(listOf(a, b, c))
        val r = composite.beforeToolCall(context(), toolCall())
        assertEquals("[from-a]; [from-b]", r!!.content)
        assertTrue(r.isError, "refused tool result must be isError=true")
        assertEquals(
            listOf("a:beforeToolCall(x)", "b:beforeToolCall(x)", "c:beforeToolCall(x)"),
            a.recordedEvents + b.recordedEvents + c.recordedEvents
        )
    }

    @Test
    fun `beforeToolCall aggregates only the refusing hooks`() = runTest {
        val a = RecordingHook("a")
        val b = RecordingHook("b")
        val c = RecordingHook("c").apply { nextSynthetic = "from-c" }
        val composite = DefaultHookPipeline(listOf(a, b, c))
        val r = composite.beforeToolCall(context(), toolCall())
        assertEquals("[from-c]", r!!.content)
        assertEquals(
            listOf("a:beforeToolCall(x)", "b:beforeToolCall(x)", "c:beforeToolCall(x)"),
            a.recordedEvents + b.recordedEvents + c.recordedEvents
        )
    }

    @Test
    fun `beforeToolCall aggregates refuses in registration order`() = runTest {
        val a = RecordingHook("a").apply { nextSynthetic = "perm-denied" }
        val b = RecordingHook("b").apply { nextSynthetic = "quota-exhausted" }
        val c = RecordingHook("c").apply { nextSynthetic = "tool-disabled" }
        val composite = DefaultHookPipeline(listOf(a, b, c))
        val r = composite.beforeToolCall(context(), toolCall())
        assertEquals("[perm-denied]; [quota-exhausted]; [tool-disabled]", r!!.content)
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
        val final = composite.afterToolCall(context(), toolCall(), initial, false, 5)
        assertEquals("b-out", final.content, "c didn't rewrite - final is b's output")
        assertEquals(listOf("a:afterToolCall(x,raw)"), a.recordedEvents)
        assertEquals(listOf("b:afterToolCall(x,a-out)"), b.recordedEvents)
        assertEquals(listOf("c:afterToolCall(x,b-out)"), c.recordedEvents)
    }

    @Test
    fun `afterToolCall returns input when no hook rewrites`() = runTest {
        val composite = DefaultHookPipeline(listOf(RecordingHook("a"), RecordingHook("b")))
        val input = ToolExecutionResult("untouched")
        val output = composite.afterToolCall(context(), toolCall(), input, false, 5)
        assertSame(input, output)
    }

    // --- Exception isolation ---

    @Test
    fun `exception in beforeLlmCall is swallowed and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out HookEvent>> = setOf(AgentHookEvent.BeforeLlmCall::class)
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
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
            override val events: Set<kotlin.reflect.KClass<out HookEvent>> = setOf(AgentHookEvent.AfterLlmResponse::class)
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(throwing, b))
        composite.afterLlmResponse(context(1), emptyResponse())
        assertEquals(listOf("b:afterLlmResponse(1)"), b.recordedEvents)
    }

    @Test
    fun `exception in onRunFailed is swallowed and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out HookEvent>> = setOf(AgentHookEvent.RunFailed::class)
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(throwing, b))
        composite.onRunFailed(context(1), AgentException.LlmError(RuntimeException("orig")))
        assertEquals(listOf("b:onRunFailed(LlmError)"), b.recordedEvents)
    }

    @Test
    fun `exception in onRunCompleted is swallowed and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out HookEvent>> = setOf(AgentHookEvent.RunCompleted::class)
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(throwing, b))
        val r = emptyResponse()
        composite.onRunCompleted(context(1), AgentResult(r.message, 1, emptyList(), null))
        assertEquals(listOf("b:onRunCompleted(iter=1)"), b.recordedEvents)
    }

    @Test
    fun `exception in beforeToolCall is treated as Continue and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out HookEvent>> = setOf(AgentHookEvent.BeforeToolCall::class)
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b").apply { nextSynthetic = "from-b" }
        val composite = DefaultHookPipeline(listOf(throwing, b))
        val r = composite.beforeToolCall(context(), toolCall())
        assertEquals("[from-b]", r!!.content, "throwing hook's exception should be swallowed, b's Refuse wins")
    }

    @Test
    fun `exception in afterToolCall keeps previous value and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out HookEvent>> = setOf(AgentHookEvent.AfterToolCall::class)
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b").apply { nextRewritten = ToolExecutionResult("b-out") }
        val composite = DefaultHookPipeline(listOf(throwing, b))
        val input = ToolExecutionResult("raw")
        val out = composite.afterToolCall(context(), toolCall(), input, false, 5)
        assertEquals("b-out", out.content)
    }

    @Test
    fun `CancellationException in beforeLlmCall propagates and stops fan-out`() = runTest {
        val throwing = object : Hook {
            override val name: String = "throwing"
            override val events: Set<kotlin.reflect.KClass<out HookEvent>> = setOf(AgentHookEvent.BeforeLlmCall::class)
            override val priority: Int = Int.MAX_VALUE
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
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
            override val events: Set<kotlin.reflect.KClass<out HookEvent>> = setOf(AgentHookEvent.BeforeToolCall::class)
            override val priority: Int = Int.MAX_VALUE
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
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
            override val events: Set<kotlin.reflect.KClass<out HookEvent>> = setOf(AgentHookEvent.AfterToolCall::class)
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
                throw CancellationException("cancelled")
            }
        }
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(throwing, b))
        var caught: Throwable? = null
        try {
            composite.afterToolCall(context(), toolCall(), ToolExecutionResult("x"), false, 5)
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

    // --- Event subscription matching ---

    @Test
    fun `hook with events null receives all events`() = runTest {
        val universal = object : Hook {
            override val name: String = "universal"
            override val events: Set<kotlin.reflect.KClass<out HookEvent>>? = null
            val recorded = mutableListOf<String>()
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
                recorded += event.javaClass.simpleName
                return HookResult.Continue
            }
        }
        val composite = DefaultHookPipeline(listOf(universal))
        composite.beforeLlmCall(context(1))
        composite.afterLlmResponse(context(1), emptyResponse())
        composite.beforeToolCall(context(), toolCall())
        assertEquals(
            listOf("BeforeLlmCall", "AfterLlmResponse", "BeforeToolCall"),
            universal.recorded
        )
    }

    @Test
    fun `hook subscribing to parent AgentHookEvent receives all agent events`() = runTest {
        val parentHook = object : Hook {
            override val name: String = "parentHook"
            override val events: Set<kotlin.reflect.KClass<out HookEvent>> = setOf(AgentHookEvent::class)
            val recorded = mutableListOf<String>()
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
                recorded += event.javaClass.simpleName
                return HookResult.Continue
            }
        }
        val composite = DefaultHookPipeline(listOf(parentHook))
        composite.beforeLlmCall(context(1))
        composite.afterLlmResponse(context(1), emptyResponse())
        composite.beforeMemoryCompress(context(1), emptyList())
        assertEquals(
            listOf("BeforeLlmCall", "AfterLlmResponse", "BeforeMemoryCompress"),
            parentHook.recorded
        )
    }

    @Test
    fun `hook subscribing to specific event only receives that event`() = runTest {
        val specificHook = object : Hook {
            override val name: String = "specificHook"
            override val events: Set<kotlin.reflect.KClass<out HookEvent>> = setOf(AgentHookEvent.BeforeLlmCall::class)
            val recorded = mutableListOf<String>()
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
                recorded += event.javaClass.simpleName
                return HookResult.Continue
            }
        }
        val composite = DefaultHookPipeline(listOf(specificHook))
        composite.beforeLlmCall(context(1))
        composite.afterLlmResponse(context(1), emptyResponse())
        assertEquals(listOf("BeforeLlmCall"), specificHook.recorded)
    }

    @Test
    fun `getHooks returns all hooks when events is null`() {
        val a = object : Hook {
            override val name: String = "a"
            override val events: Set<kotlin.reflect.KClass<out HookEvent>>? = null
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult = HookResult.Continue
        }
        val b = RecordingHook("b", setOf(AgentHookEvent.BeforeLlmCall::class))
        val composite = DefaultHookPipeline(listOf(a, b))
        assertEquals(2, composite.getHooks(AgentHookEvent.BeforeLlmCall::class).size)
        assertEquals(1, composite.getHooks(AgentHookEvent.RunFailed::class).size)
    }

    @Test
    fun `getHooks returns hooks subscribed to parent class`() {
        val parentSub = object : Hook {
            override val name: String = "parentSub"
            override val events: Set<kotlin.reflect.KClass<out HookEvent>> = setOf(AgentHookEvent::class)
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult = HookResult.Continue
        }
        val specificSub = object : Hook {
            override val name: String = "specificSub"
            override val events: Set<kotlin.reflect.KClass<out HookEvent>> = setOf(AgentHookEvent.RunFailed::class)
            override suspend fun execute(event: HookEvent, context: HookContext): HookResult = HookResult.Continue
        }
        val composite = DefaultHookPipeline(listOf(parentSub, specificSub))
        assertEquals(2, composite.getHooks(AgentHookEvent.RunFailed::class).size)
        assertEquals(1, composite.getHooks(AgentHookEvent.BeforeLlmCall::class).size)
    }

    // --- Priority ordering ---

    @Test
    fun `hooks are sorted by priority descending`() = runTest {
        val low = RecordingHook("low").apply { priorityOverride = 10 }
        val high = RecordingHook("high").apply { priorityOverride = 100 }
        val mid = RecordingHook("mid").apply { priorityOverride = 50 }
        val composite = DefaultHookPipeline(listOf(low, high, mid))
        composite.beforeLlmCall(context(1))

        assertEquals(
            listOf("high:beforeLlmCall(1)", "mid:beforeLlmCall(1)", "low:beforeLlmCall(1)"),
            listOf(high, mid, low).flatMap { it.recordedEvents }
        )
    }

    @Test
    fun `register adds hook and re-sorts by priority`() = runTest {
        val low = RecordingHook("low").apply { priorityOverride = 10 }
        val composite = DefaultHookPipeline(listOf(low))
        val high = RecordingHook("high").apply { priorityOverride = 100 }
        composite.register(high)

        composite.beforeLlmCall(context(1))
        assertEquals(
            listOf("high:beforeLlmCall(1)", "low:beforeLlmCall(1)"),
            listOf(high, low).flatMap { it.recordedEvents }
        )
    }

    @Test
    fun `unregister removes hook by name`() = runTest {
        val a = RecordingHook("a")
        val b = RecordingHook("b")
        val composite = DefaultHookPipeline(listOf(a, b))
        composite.unregister("a")

        composite.beforeLlmCall(context(1))
        assertTrue(a.recordedEvents.isEmpty())
        assertEquals(listOf("b:beforeLlmCall(1)"), b.recordedEvents)
    }

    @Test
    fun `unregister non-existent name does nothing`() = runTest {
        val a = RecordingHook("a")
        val composite = DefaultHookPipeline(listOf(a))
        composite.unregister("nonexistent")

        composite.beforeLlmCall(context(1))
        assertEquals(listOf("a:beforeLlmCall(1)"), a.recordedEvents)
    }

    // --- DefaultHookPipeline constructor ---

    @Test
    fun `no-arg constructor creates empty pipeline`() = runTest {
        val composite = DefaultHookPipeline()
        assertTrue(composite.getHooks().isEmpty())
        assertNull(composite.beforeToolCall(context(), toolCall()))
    }
}
