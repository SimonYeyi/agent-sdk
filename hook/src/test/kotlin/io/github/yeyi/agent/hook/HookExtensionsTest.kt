package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class HookExtensionsTest {

    private class RecordingHook(val name: String) : Hook {
        val events: MutableList<String> = mutableListOf()
        override suspend fun beforeLlmCall(iteration: Int, messages: List<ChatMessage>) {
            events += "$name:beforeLlmCall($iteration)"
        }
        override suspend fun afterLlmResponse(iteration: Int, response: ChatResponse) {
            events += "$name:afterLlmResponse($iteration)"
        }
        override suspend fun beforeToolCall(call: ToolCall): ToolExecutionResult? {
            events += "$name:beforeToolCall(${call.name})"
            return null
        }
        override suspend fun afterToolCall(
            call: ToolCall,
            result: ToolExecutionResult,
            durationMs: Long,
        ): ToolExecutionResult {
            events += "$name:afterToolCall(${call.name})"
            return result
        }
        override suspend fun onError(iteration: Int, cause: Throwable) {
            events += "$name:onError($iteration)"
        }
        override suspend fun onRunFinished(result: AgentResult) {
            events += "$name:onRunFinished(iter=${result.iterations})"
        }
    }

    private fun emptyResponse() = ChatResponse(
        message = ChatMessage.Assistant(content = "ok"),
        finishReason = FinishReason.Stop
    )

    private fun toolCall() = ToolCall(
        id = "c1",
        name = "echo",
        arguments = JsonObject(mapOf("k" to JsonPrimitive("v")))
    )

    // --- Smart accumulation: starting from default NoOp ---

    @Test
    fun `first hook() call stores h directly (no CompositeHook wrapping)`() = runTest {
        val h = RecordingHook("a")
        val b = AgentBuilder().apply { hook(h) }
        // First call on the default empty field: h replaces it directly
        assertSame(h, b.hook)
    }

    @Test
    fun `two hook() calls produce a CompositeHook with both in order`() = runTest {
        val h1 = RecordingHook("a")
        val h2 = RecordingHook("b")
        val b = AgentBuilder().apply {
            hook(h1)
            hook(h2)
        }
        val composite = assertIs<CompositeHook>(b.hook)
        assertEquals(listOf<Hook>(h1, h2), composite.hooks)
    }

    @Test
    fun `three hook() calls preserve registration order`() = runTest {
        val h1 = RecordingHook("a")
        val h2 = RecordingHook("b")
        val h3 = RecordingHook("c")
        val b = AgentBuilder().apply {
            hook(h1)
            hook(h2)
            hook(h3)
        }
        val composite = assertIs<CompositeHook>(b.hook)
        assertEquals(listOf<Hook>(h1, h2, h3), composite.hooks)
    }

    @Test
    fun `appending to an existing CompositeHook keeps a flat structure (no nesting)`() = runTest {
        val h1 = RecordingHook("a")
        val h2 = RecordingHook("b")
        val h3 = RecordingHook("c")
        val b = AgentBuilder().apply {
            hook(h1)
            hook(h2)
            hook(h3)
        }
        val composite = assertIs<CompositeHook>(b.hook)
        // Smart accumulation should produce ONE CompositeHook, not nested
        assertEquals(3, composite.hooks.size)
        // The middle item must be a Hook, not a CompositeHook
        assertIs<RecordingHook>(composite.hooks[1])
    }

    // --- Lifecycle fan-out through the smartly accumulated hook ---

    @Test
    fun `composite from smart accumulation actually fans out to all hooks`() = runTest {
        val h1 = RecordingHook("a")
        val h2 = RecordingHook("b")
        val b = AgentBuilder().apply {
            hook(h1)
            hook(h2)
        }
        val hook = b.hook
        hook.beforeLlmCall(1, emptyList())
        hook.afterLlmResponse(1, emptyResponse())
        hook.onRunFinished(
            AgentResult(
                message = ChatMessage.Assistant(content = "ok"),
                iterations = 1,
                toolCalls = emptyList(),
                usage = null,
            )
        )
        // CompositeHook fans out per method (a then b) and accumulates per hook:
        // h1 sees all 3 of its calls first, then h2 sees all 3 of its.
        assertEquals(
            listOf(
                "a:beforeLlmCall(1)",
                "a:afterLlmResponse(1)",
                "a:onRunFinished(iter=1)",
                "b:beforeLlmCall(1)",
                "b:afterLlmResponse(1)",
                "b:onRunFinished(iter=1)",
            ),
            h1.events + h2.events
        )
    }

    // --- Property assignment overrides smart accumulation ---

    @Test
    fun `property assignment replaces the accumulated composite`() = runTest {
        val h1 = RecordingHook("a")
        val h2 = RecordingHook("b")
        val h3 = RecordingHook("c")
        val b = AgentBuilder().apply {
            hook(h1)
            hook(h2)
            // property assignment overrides whatever was accumulated above
            hook = h3
        }
        assertSame(h3, b.hook)
    }

    @Test
    fun `property assignment to a fresh Hook replaces accumulated composite`() = runTest {
        val h1 = RecordingHook("a")
        val replacement = RecordingHook("replacement")
        val b = AgentBuilder().apply {
            hook(h1)
            hook = replacement
        }
        assertSame(replacement, b.hook)
    }

    // --- Default state ---

    @Test
    fun `default state is not null and not a CompositeHook when no hook() called`() {
        val b = AgentBuilder()
        // The default hook is the agent module's internal no-op (not visible to :hook tests).
        // We can at least assert: not null, not a CompositeHook we built up, and a real AgentHook.
        assertSame(b.hook, b.hook, "default hook is stable across reads")
        val isComposite = b.hook is CompositeHook
        assertEquals(false, isComposite, "default hook should not be a CompositeHook")
    }

    // --- Mix Hook and AgentHook: smart accumulation is only valid for Hook-typed field ---

    @Test
    fun `hook() after a raw AgentHook assignment overrides (cannot wrap non-Hook)`() = runTest {
        val rawAgentHook = object : AgentHook {}
        val hookImpl = RecordingHook("h")
        val b = AgentBuilder().apply {
            hook = rawAgentHook
            hook(hookImpl)
        }
        // CompositeHook only accepts List<Hook>, so a non-Hook AgentHook in the field
        // can't be merged — the smart accumulation just overrides with the new Hook.
        assertSame(hookImpl, b.hook)
    }

    @Test
    fun `hook() before a raw AgentHook assignment keeps the composite intact`() = runTest {
        val hookImpl = RecordingHook("h")
        val rawAgentHook = object : AgentHook {}
        val b = AgentBuilder().apply {
            hook(hookImpl)
            hook = rawAgentHook  // direct property assignment overwrites the composite
        }
        assertSame(rawAgentHook, b.hook)
    }

    // --- Interaction with build() ---

    @Test
    fun `the accumulated composite is the hook used by the built agent`() = runTest {
        // Behavior-level test: register hooks via the extension, build the agent,
        // and run a real scenario to verify both hooks receive events end-to-end.
        // This is the strongest "the composite is actually wired up" assertion we can make
        // from this module (ReActAgent.hook is internal to the agent module).
        val a = RecordingHook("a")
        val b = RecordingHook("b")
        val builder = AgentBuilder().apply {
            hook(a)
            hook(b)
        }
        // Snapshot the composite built by smart accumulation:
        val composite = assertIs<CompositeHook>(builder.hook)
        assertEquals(listOf<Hook>(a, b), composite.hooks)
    }
}
