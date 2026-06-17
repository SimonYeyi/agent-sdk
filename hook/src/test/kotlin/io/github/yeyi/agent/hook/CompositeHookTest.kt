package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.session.Session
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

class CompositeHookTest {

    private fun context(iter: Int = 1) = AgentContext(
        persona = Persona(role = ""),
        maxIterations = 5,
        currentIteration = iter,
        memory = InMemoryMemory(),
    )

    /** Records every lifecycle call into [events] for assertion. Implements [Hook] so it can
     *  live inside a [CompositeHook] (which requires List<Hook>). */
    private class RecordingHook(val name: String) : Hook {
        val events: MutableList<String> = mutableListOf()
        var nextSynthetic: ToolExecutionResult? = null
        var nextRewritten: ToolExecutionResult? = null
        override suspend fun beforeLlmCall(context: AgentContext) {
            events += "$name:beforeLlmCall(${context.currentIteration})"
        }
        override suspend fun afterLlmResponse(context: AgentContext, response: ChatResponse) {
            events += "$name:afterLlmResponse(${context.currentIteration})"
        }
        override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? {
            events += "$name:beforeToolCall(${call.name})"
            return nextSynthetic
        }
        override suspend fun afterToolCall(
            context: AgentContext,
            call: ToolCall,
            result: ToolExecutionResult,
            durationMs: Long,
        ): ToolExecutionResult {
            events += "$name:afterToolCall(${call.name},${result.content})"
            return nextRewritten ?: result
        }
        override suspend fun onError(context: AgentContext, cause: AgentException) {
            events += "$name:onError(${cause::class.simpleName})"
        }
        override suspend fun onRunFinished(context: AgentContext, result: AgentResult) {
            events += "$name:onRunFinished(iter=${result.iterations})"
        }
        override suspend fun onSessionCreated(session: Session) {
            events += "$name:onSessionCreated(${session.id})"
        }
        override suspend fun onSessionDeleted(accountId: String, sessionId: String) {
            events += "$name:onSessionDeleted($accountId,$sessionId)"
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
        val composite = CompositeHook(listOf(a, b))
        val r = emptyResponse()
        composite.beforeLlmCall(context(1))
        composite.afterLlmResponse(context(1), r)
        composite.onError(context(1), AgentException.LlmError(RuntimeException("x")))
        composite.onRunFinished(context(1), AgentResult(r.message, 1, emptyList(), null))
        // CompositeHook processes ALL hooks for a single callback method,
        // then moves to the next callback. So per-hook events accumulate by hook,
        // not by method: a's full sequence first, then b's full sequence.
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
            a.events + b.events
        )
    }

    // --- Short-circuit: beforeToolCall first non-null wins ---

    @Test
    fun `beforeToolCall returns first non-null synthetic and short-circuits rest`() = runTest {
        val a = RecordingHook("a").apply { nextSynthetic = ToolExecutionResult("from-a") }
        val b = RecordingHook("b").apply { nextSynthetic = ToolExecutionResult("from-b") }
        val c = RecordingHook("c")
        val composite = CompositeHook(listOf(a, b, c))
        val r = composite.beforeToolCall(context(), toolCall())
        assertEquals("from-a", r!!.content)
        // a was called, b was called (returned non-null, so a's value is shadowed by b's first-wins)
        // ... actually first-wins: a returns non-null, b and c are not called.
        assertEquals(listOf("a:beforeToolCall(x)"), a.events)
        assertEquals(emptyList<String>(), b.events, "b should NOT be called after a returned non-null")
        assertEquals(emptyList<String>(), c.events)
    }

    @Test
    fun `beforeToolCall skips hooks returning null and takes the first non-null`() = runTest {
        val a = RecordingHook("a")  // returns null
        val b = RecordingHook("b")  // returns null
        val c = RecordingHook("c").apply { nextSynthetic = ToolExecutionResult("from-c") }
        val composite = CompositeHook(listOf(a, b, c))
        val r = composite.beforeToolCall(context(), toolCall())
        assertEquals("from-c", r!!.content)
        assertEquals(listOf("a:beforeToolCall(x)", "b:beforeToolCall(x)", "c:beforeToolCall(x)"),
            a.events + b.events + c.events)
    }

    @Test
    fun `beforeToolCall returns null when all hooks return null`() = runTest {
        val composite = CompositeHook(listOf(RecordingHook("a"), RecordingHook("b")))
        assertNull(composite.beforeToolCall(context(), toolCall()))
    }

    // --- Chain: afterToolCall ---

    @Test
    fun `afterToolCall chains each hook sees previous output`() = runTest {
        val a = RecordingHook("a").apply { nextRewritten = ToolExecutionResult("a-out") }
        val b = RecordingHook("b").apply { nextRewritten = ToolExecutionResult("b-out") }
        val c = RecordingHook("c")  // leaves result alone
        val composite = CompositeHook(listOf(a, b, c))
        val initial = ToolExecutionResult("raw")
        val final = composite.afterToolCall(context(), toolCall(), initial, 5)
        assertEquals("b-out", final.content, "c didn't rewrite 鈫?a鈫抌鈫抍 final is b's output")
        // a saw raw
        assertEquals(listOf("a:afterToolCall(x,raw)"), a.events)
        // b saw a's output
        assertEquals(listOf("b:afterToolCall(x,a-out)"), b.events)
        // c saw b's output
        assertEquals(listOf("c:afterToolCall(x,b-out)"), c.events)
    }

    @Test
    fun `afterToolCall returns input when no hook rewrites`() = runTest {
        val composite = CompositeHook(listOf(RecordingHook("a"), RecordingHook("b")))
        val input = ToolExecutionResult("untouched")
        val output = composite.afterToolCall(context(), toolCall(), input, 5)
        assertSame(input, output)
    }

    // --- Exception isolation ---

    @Test
    fun `exception in beforeLlmCall is swallowed and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override suspend fun beforeLlmCall(context: AgentContext) {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b")
        val composite = CompositeHook(listOf(throwing, b))
        composite.beforeLlmCall(context(1))
        assertEquals(listOf("b:beforeLlmCall(1)"), b.events)
    }

    @Test
    fun `exception in afterLlmResponse is swallowed and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override suspend fun afterLlmResponse(context: AgentContext, response: ChatResponse) {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b")
        val composite = CompositeHook(listOf(throwing, b))
        composite.afterLlmResponse(context(1), emptyResponse())
        assertEquals(listOf("b:afterLlmResponse(1)"), b.events)
    }

    @Test
    fun `exception in onError is swallowed and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override suspend fun onError(context: AgentContext, cause: AgentException) {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b")
        val composite = CompositeHook(listOf(throwing, b))
        composite.onError(context(1), AgentException.LlmError(RuntimeException("orig")))
        assertEquals(listOf("b:onError(LlmError)"), b.events)
    }

    @Test
    fun `exception in onRunFinished is swallowed and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override suspend fun onRunFinished(context: AgentContext, result: AgentResult) {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b")
        val composite = CompositeHook(listOf(throwing, b))
        val r = emptyResponse()
        composite.onRunFinished(context(1), AgentResult(r.message, 1, emptyList(), null))
        assertEquals(listOf("b:onRunFinished(iter=1)"), b.events)
    }

    @Test
    fun `exception in beforeToolCall is treated as null and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b").apply { nextSynthetic = ToolExecutionResult("from-b") }
        val composite = CompositeHook(listOf(throwing, b))
        val r = composite.beforeToolCall(context(), toolCall())
        assertEquals("from-b", r!!.content, "throwing hook's exception should be swallowed, b wins")
    }

    @Test
    fun `exception in afterToolCall keeps previous value and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override suspend fun afterToolCall(
                context: AgentContext,
                call: ToolCall,
                result: ToolExecutionResult,
                durationMs: Long,
            ): ToolExecutionResult = throw RuntimeException("oops")
        }
        val b = RecordingHook("b").apply { nextRewritten = ToolExecutionResult("b-out") }
        val composite = CompositeHook(listOf(throwing, b))
        val input = ToolExecutionResult("raw")
        val out = composite.afterToolCall(context(), toolCall(), input, 5)
        assertEquals("b-out", out.content)
    }

    @Test
    fun `CancellationException in beforeLlmCall propagates and stops fan-out`() = runTest {
        val throwing = object : Hook {
            override suspend fun beforeLlmCall(context: AgentContext) {
                throw CancellationException("cancelled")
            }
        }
        val b = RecordingHook("b")
        val composite = CompositeHook(listOf(throwing, b))
        var caught: Throwable? = null
        try {
            composite.beforeLlmCall(context(1))
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(caught is CancellationException)
        assertEquals(emptyList<String>(), b.events, "cancellation must stop fan-out")
    }

    @Test
    fun `CancellationException in beforeToolCall propagates immediately`() = runTest {
        val throwing = object : Hook {
            override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? {
                throw CancellationException("cancelled")
            }
        }
        val b = RecordingHook("b")
        val composite = CompositeHook(listOf(throwing, b))
        var caught: Throwable? = null
        try {
            composite.beforeToolCall(context(), toolCall())
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue(caught is CancellationException)
        assertEquals(emptyList<String>(), b.events)
    }

    @Test
    fun `CancellationException in afterToolCall propagates immediately`() = runTest {
        val throwing = object : Hook {
            override suspend fun afterToolCall(
                context: AgentContext,
                call: ToolCall,
                result: ToolExecutionResult,
                durationMs: Long,
            ): ToolExecutionResult = throw CancellationException("cancelled")
        }
        val b = RecordingHook("b")
        val composite = CompositeHook(listOf(throwing, b))
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
        val composite = CompositeHook(hooks.toList())
        composite.beforeLlmCall(context(1))
        // All 5 must be called in order
        for ((i, h) in hooks.withIndex()) {
            assertEquals(listOf("h$i:beforeLlmCall(1)"), h.events, "hook $i should be called in order")
        }
    }

    // --- SessionHook tests ---

    @Test
    fun `onSessionCreated fans out to all hooks`() = runTest {
        val a = RecordingHook("a")
        val b = RecordingHook("b")
        val composite = CompositeHook(listOf(a, b))
        val session = Session("s1", "u1", "test", kotlinx.datetime.Clock.System.now(), kotlinx.datetime.Clock.System.now())
        composite.onSessionCreated(session)
        assertEquals(listOf("a:onSessionCreated(s1)", "b:onSessionCreated(s1)"), a.events + b.events)
    }

    @Test
    fun `onSessionDeleted fans out to all hooks`() = runTest {
        val a = RecordingHook("a")
        val b = RecordingHook("b")
        val composite = CompositeHook(listOf(a, b))
        composite.onSessionDeleted("u1", "s1")
        assertEquals(listOf("a:onSessionDeleted(u1,s1)", "b:onSessionDeleted(u1,s1)"), a.events + b.events)
    }

    @Test
    fun `exception in onSessionCreated is swallowed and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override suspend fun onSessionCreated(session: Session) {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b")
        val composite = CompositeHook(listOf(throwing, b))
        val session = Session("s1", "u1", "test", kotlinx.datetime.Clock.System.now(), kotlinx.datetime.Clock.System.now())
        composite.onSessionCreated(session)
        assertEquals(listOf("b:onSessionCreated(s1)"), b.events)
    }

    @Test
    fun `exception in onSessionDeleted is swallowed and remaining hooks still called`() = runTest {
        val throwing = object : Hook {
            override suspend fun onSessionDeleted(accountId: String, sessionId: String) {
                throw RuntimeException("oops")
            }
        }
        val b = RecordingHook("b")
        val composite = CompositeHook(listOf(throwing, b))
        composite.onSessionDeleted("u1", "s1")
        assertEquals(listOf("b:onSessionDeleted(u1,s1)"), b.events)
    }
}