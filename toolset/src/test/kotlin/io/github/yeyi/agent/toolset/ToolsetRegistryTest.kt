package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolsetRegistryTest {

    private class StubSubTool(
        override val name: String,
    ) : Tool {
        override val description: String = "stub"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
            ToolExecutionResult("ok")
    }

    private object UnusedLlm : LlmProvider {
        override val name: String = "unused"
        override suspend fun chat(request: ChatRequest): ChatResponse =
            error("LlmProvider.chat must not be called in ToolsetRegistryTest")
        override fun chatStream(request: ChatRequest): Flow<StreamEvent> =
            flowOf(StreamEvent.Error(IllegalStateException("unused")))
    }

    private fun emptyContext(): ToolContext = ToolContext(
        toolCallId = "test",
        agentContext = AgentContext(
            persona = Persona(""),
            maxIterations = 1,
            currentIteration = 1,
            memory = InMemoryMemory(),
            llmProvider = UnusedLlm,
            tools = emptyList(),
            maxRounds = 20,
        ),
    )

    // ---------- Registry basics ----------

    @Test
    fun `capabilityName is toolset`() {
        val r = ToolsetRegistry()
        assertEquals("toolset", r.capabilityType)
        assertEquals(Toolset.CAPABILITY_TYPE, r.capabilityType)
    }

    @Test
    fun `register stores a toolset visible via all`() {
        val r = ToolsetRegistry()
        val ts = Toolset("weather", "d")
        r.register(ts)
        assertEquals(listOf("weather"), r.all().map { it.name })
    }

    @Test
    fun `register iterable stores multiple toolsets`() {
        val r = ToolsetRegistry()
        r.register(listOf(Toolset("a", "d1"), Toolset("b", "d2"), Toolset("c", "d3")))
        assertEquals(setOf("a", "b", "c"), r.all().map { it.name }.toSet())
        assertEquals(3, r.all().size)
    }

    @Test
    fun `register duplicate toolset name throws`() {
        val r = ToolsetRegistry()
        r.register(Toolset("dup", "d"))
        assertFailsWith<IllegalArgumentException> {
            r.register(Toolset("dup", "d2"))
        }
    }

    @Test
    fun `get returns the same toolset instance by name`() = runTest {
        val r = ToolsetRegistry()
        val ts = Toolset("weather", "d").apply { add(StubSubTool("inner")) }
        r.register(ts)
        val resolved = r.get("weather")
        assertSame(ts, resolved)
        // Sanity: the resolved toolset is functional (dispatch works through the instance)
        val out = resolved.dispatch("inner", JsonNull, emptyContext())
        assertEquals("ok", out.content)
    }

    @Test
    fun `get unknown name throws NoSuchElementException`() {
        val r = ToolsetRegistry()
        assertFailsWith<NoSuchElementException> {
            r.get("missing")
        }
    }

    @Test
    fun `get unknown name after some toolsets registered throws and mentions the missing name`() {
        val r = ToolsetRegistry()
        r.register(Toolset("a", "d"))
        val ex = assertFailsWith<NoSuchElementException> { r.get("zzz") }
        assertTrue("zzz" in (ex.message ?: ""), "exception should mention the missing name, got: ${ex.message}")
    }

    @Test
    fun `all returns empty when nothing is registered`() {
        val r = ToolsetRegistry()
        assertEquals(0, r.all().size)
    }

    @Test
    fun `all returns the registered toolsets without crashing when names are arbitrary`() {
        val r = ToolsetRegistry()
        r.register(listOf(Toolset("z", "d"), Toolset("a", "d"), Toolset("m", "d")))
        // The registry doesn't guarantee insertion order (backed by ConcurrentHashMap);
        // we only assert every registered toolset is discoverable via all().
        assertEquals(setOf("z", "a", "m"), r.all().map { it.name }.toSet())
        assertEquals(3, r.all().size)
    }

    @Test
    fun `unregisterAll clears the registry`() {
        val r = ToolsetRegistry()
        r.register(listOf(Toolset("a", "d"), Toolset("b", "d")))
        r.unregisterAll()
        assertEquals(0, r.all().size)
        assertFailsWith<NoSuchElementException> { r.get("a") }
    }

    @Test
    fun `registry can be re-populated after unregisterAll`() {
        val r = ToolsetRegistry()
        r.register(Toolset("old", "d"))
        r.unregisterAll()
        r.register(Toolset("new", "d"))
        assertEquals(listOf("new"), r.all().map { it.name })
    }

    @Test
    fun `all on a registry with no tools is empty and isEmpty check holds`() {
        val r = ToolsetRegistry()
        assertTrue(r.all().isEmpty())
    }
}
