package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.toDefinition
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolsetTest {

    private class StubTool(
        override val name: String,
        override val description: String = "stub tool",
        override val parametersSchema: ToolParameters = ToolParameters.Empty,
        private val result: ToolExecutionResult = ToolExecutionResult.success("ok"),
    ) : Tool {
        val execCalls: MutableList<JsonElement> = mutableListOf()
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
            execCalls += arguments
            return result
        }
    }

    private object UnusedLlm : LlmProvider {
        override val name: String = "unused"
        override suspend fun chat(request: ChatRequest): ChatResponse =
            error("LlmProvider.chat must not be called in ToolsetTest")
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> =
            flowOf(ChatResponseEvent.Error(IllegalStateException("unused")))
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

    // ---------- Factory & constants ----------

    @Test
    fun `CAPABILITY_TYPE is toolset`() {
        assertEquals("toolset", Toolset.CAPABILITY_TYPE)
    }

    @Test
    fun `factory produces a Toolset with given name and description`() {
        val ts = Toolset("weather", "天气相关工具集")
        assertEquals("weather", ts.name)
        assertEquals("天气相关工具集", ts.description)
    }

    // ---------- add ----------

    @Test
    fun `add stores a single sub tool`() = runTest {
        val ts = Toolset("weather", "d")
        ts.add(StubTool("get_weather"))
        assertEquals("ok", ts.dispatch("get_weather", JsonNull, emptyContext()).parts.text)
    }

    @Test
    fun `add iterable stores multiple sub tools`() = runTest {
        val ts = Toolset("weather", "d")
        ts.add(listOf(StubTool("a"), StubTool("b"), StubTool("c")))
        assertEquals(3, ts.all().map { it.toDefinition() }.size)
        assertEquals("ok", ts.dispatch("b", JsonNull, emptyContext()).parts.text)
    }

    @Test
    fun `add iterable with empty list is a no-op`() = runTest {
        val ts = Toolset("empty", "d")
        ts.add(emptyList<Tool>())
        assertEquals(0, ts.all().map { it.toDefinition() }.size)
    }

    @Test
    fun `add duplicate sub tool name throws IllegalArgumentException`() {
        val ts = Toolset("weather", "d")
        ts.add(StubTool("get_weather"))
        assertFailsWith<IllegalArgumentException> {
            ts.add(StubTool("get_weather"))
        }
    }

    @Test
    fun `add duplicate within iterable throws and toolset remains usable for other names`() = runTest {
        // forEach stops at the throw, so the first "a" stays; the user can still
        // add a different name afterward.
        val ts = Toolset("weather", "d")
        assertFailsWith<IllegalArgumentException> {
            ts.add(listOf(StubTool("a"), StubTool("a")))
        }
        ts.add(StubTool("b"))
        assertEquals("ok", ts.dispatch("b", JsonNull, emptyContext()).parts.text)
    }

    @Test
    fun `sub tools with the same name in DIFFERENT toolsets are independent`() = runTest {
        val ts1 = Toolset("a", "d1").apply { add(StubTool("x", result = ToolExecutionResult.success("from-a"))) }
        val ts2 = Toolset("b", "d2").apply { add(StubTool("x", result = ToolExecutionResult.success("from-b"))) }
        assertEquals("from-a", ts1.dispatch("x", JsonNull, emptyContext()).parts.text)
        assertEquals("from-b", ts2.dispatch("x", JsonNull, emptyContext()).parts.text)
    }

    // ---------- definitions + activate ----------

    @Test
    fun `definitions returns one ToolDefinition per sub tool in registration order`() = runTest {
        val ts = Toolset("ordered", "d")
        ts.add(StubTool("z"))
        ts.add(StubTool("a"))
        ts.add(StubTool("m"))
        val names = ts.all().map { it.toDefinition() }.map { it.name }
        assertEquals(listOf("z", "a", "m"), names)
    }

    @Test
    fun `definitions exposes each sub tool's name description and parametersSchema`() = runTest {
        val ts = Toolset("weather", "d")
        val schemaJson = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
        ts.add(StubTool(
            name = "get_weather",
            description = "获取天气",
            parametersSchema = ToolParameters.JsonSchema(schemaJson),
        ))
        val def = ts.all().map { it.toDefinition() }.single()
        assertEquals("get_weather", def.name)
        assertEquals("获取天气", def.description)
        val schema = def.parametersSchema
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("city"), schema["properties"]!!.jsonObject.keys)
        assertEquals(listOf("city"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `definitions uses empty JsonObject for Empty parametersSchema`() = runTest {
        val ts = Toolset("t", "d")
        ts.add(StubTool("noop", parametersSchema = ToolParameters.Empty))
        val params = ts.all().map { it.toDefinition() }.single().parametersSchema
        assertEquals("object", params["type"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), params["properties"]!!.jsonObject)
    }

    @Test
    fun `activate with no sub tools still shows toolset name`() = runTest {
        val ts = Toolset("empty", "d")
        val result = ts.activate(null, ToolsetContext())
        assertTrue("Toolset 'empty'" in result)
    }

    @Test
    fun `activate header contains the toolset name and the definitions body`() = runTest {
        val ts = Toolset("weather", "d")
        ts.add(StubTool("a", description = "tool-a"))
        ts.add(StubTool("b", description = "tool-b"))
        val result = ts.activate(null, ToolsetContext())
        val header = result.substringBefore('\n')
        assertTrue("Toolset 'weather'" in header, "expected toolset name in header, got: $header")
        assertTrue("a" in result && "b" in result, "definitions body should list both tools")
    }

    // ---------- dispatch (routing) ----------

    @Test
    fun `dispatch routes to the named sub tool and returns its result`() = runTest {
        val sub = StubTool("get_weather", result = ToolExecutionResult.success("sunny"))
        val ts = Toolset("weather", "d").apply { add(sub) }
        val out = ts.dispatch("get_weather", JsonNull, emptyContext())
        assertFalse(out.isError)
        assertEquals("sunny", out.parts.text)
    }

    @Test
    fun `dispatch forwards arguments to the sub tool`() = runTest {
        val sub = StubTool("echo")
        val ts = Toolset("t", "d").apply { add(sub) }
        val args = buildJsonObject { put("city", JsonPrimitive("Beijing")) }
        ts.dispatch("echo", args, emptyContext())
        assertEquals(1, sub.execCalls.size)
        assertEquals(args, sub.execCalls.single())
    }

    @Test
    fun `dispatch returns ToolNotFound error for unknown sub tool name`() = runTest {
        val ts = Toolset("t", "d").apply { add(StubTool("known")) }
        val out = ts.dispatch("unknown", JsonNull, emptyContext())
        assertTrue(out.isError, "expected isError=true, got content=${out.parts.text}")
        assertTrue("'unknown'" in out.parts.text, "error should mention the missing name, got: ${out.parts.text}")
        assertTrue("known" in out.parts.text, "error should list available tools, got: ${out.parts.text}")
    }

    @Test
    fun `dispatch returns the sub tool's error result unchanged`() = runTest {
        val sub = StubTool("boom", result = ToolExecutionResult.error("kaboom"))
        val ts = Toolset("t", "d").apply { add(sub) }
        val out = ts.dispatch("boom", JsonNull, emptyContext())
        assertTrue(out.isError)
        assertEquals("kaboom", out.parts.text)
    }

    @Test
    fun `dispatch parameter shadows the toolset name by design`() = runTest {
        // The dispatch parameter is the SUB-tool name; lookup must use it, not the toolset name.
        val sub = StubTool("inner")
        val ts = Toolset("outer", "d").apply { add(sub) }
        // If `name` resolved to "outer", lookup would fail with ToolNotFound.
        val out = ts.dispatch("inner", JsonNull, emptyContext())
        assertFalse(out.isError)
    }

    // ---------- ToolsetContextFactory ----------

    @Test
    fun `ToolsetContextFactory create returns a non-null ToolsetContext`() {
        val factory = ToolsetContextFactory()
        val ctx = factory.create(emptyContext())
        assertNotNull(ctx)
    }
}
