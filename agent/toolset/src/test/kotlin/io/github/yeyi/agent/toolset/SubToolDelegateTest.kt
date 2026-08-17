package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubToolDelegateTest {

    private class CapturingTool(
        override val name: String,
        private val result: ToolExecutionResult = ToolExecutionResult.success("ok"),
    ) : Tool {
        override val description: String = "capturing"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        val execCalls: MutableList<JsonElement> = mutableListOf()
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
            execCalls += arguments
            return result
        }
    }

    private object UnusedLlm : LlmProvider {
        override val name: String = "unused"
        override suspend fun chat(request: ChatRequest): ChatResponse =
            error("LlmProvider.chat must not be called in SubToolDelegateTest")
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

    private fun buildRegistry(): Pair<ToolsetRegistry, CapturingTool> {
        val sub = CapturingTool("inner")
        val ts = Toolset("weather", "d").apply { add(sub) }
        val r = ToolsetRegistry().apply { register(ts) }
        return r to sub
    }

    // ---------- Tool identity & schema ----------

    @Test
    fun `name is sub_tool_delegate`() {
        val (r, _) = buildRegistry()
        assertEquals("sub_tool_delegate", SubToolDelegate(r).name)
    }

    @Test
    fun `schema declares toolset_name, sub_tool_name, sub_tool_arguments with required keys`() {
        val (r, _) = buildRegistry()
        val schema = SubToolDelegate(r).parametersSchema
        val parsed = when (schema) {
            is ToolParameters.JsonSchema -> Json.parseToJsonElement(schema.schema) as JsonObject
            ToolParameters.Empty -> error("expected JsonSchema, got Empty")
        }
        val props = parsed["properties"] as JsonObject
        assertTrue("toolset_name" in props)
        assertTrue("sub_tool_name" in props)
        assertTrue("sub_tool_arguments" in props)
        val required = (parsed["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
        assertTrue("toolset_name" in required)
        assertTrue("sub_tool_name" in required)
    }

    // ---------- Validation errors ----------

    @Test
    fun `execute returns error when toolset_name is missing`() = runTest {
        val (r, _) = buildRegistry()
        val out = SubToolDelegate(r).execute(
            buildJsonObject {
                put("sub_tool_name", "inner")
            },
            emptyContext(),
        )
        assertTrue(out.isError)
        assertTrue("toolset_name" in out.parts.text, "expected error to mention 'toolset_name', got: ${out.parts.text}")
    }

    @Test
    fun `execute returns error when sub_tool_name is missing`() = runTest {
        val (r, _) = buildRegistry()
        val out = SubToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "weather")
            },
            emptyContext(),
        )
        assertTrue(out.isError)
        assertTrue("sub_tool_name" in out.parts.text, "expected error to mention 'sub_tool_name', got: ${out.parts.text}")
    }

    @Test
    fun `execute returns error when both required fields are missing`() = runTest {
        val (r, _) = buildRegistry()
        val out = SubToolDelegate(r).execute(JsonObject(emptyMap()), emptyContext())
        assertTrue(out.isError)
    }

    // ---------- Routing ----------

    @Test
    fun `execute dispatches to the named sub tool and returns its result`() = runTest {
        val (r, sub) = buildRegistry()
        val out = SubToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "weather")
                put("sub_tool_name", "inner")
            },
            emptyContext(),
        )
        assertFalse(out.isError)
        assertEquals("ok", out.parts.text)
        assertEquals(1, sub.execCalls.size, "sub tool should have been invoked exactly once")
    }

    @Test
    fun `execute forwards sub_tool_arguments as the sub tool's arguments`() = runTest {
        val (r, sub) = buildRegistry()
        val args = buildJsonObject { put("city", JsonPrimitive("Beijing")) }
        SubToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "weather")
                put("sub_tool_name", "inner")
                put("sub_tool_arguments", args)
            },
            emptyContext(),
        )
        assertEquals(args, sub.execCalls.single())
    }

    @Test
    fun `execute uses JsonNull when sub_tool_arguments is absent`() = runTest {
        val (r, sub) = buildRegistry()
        SubToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "weather")
                put("sub_tool_name", "inner")
            },
            emptyContext(),
        )
        assertEquals(JsonNull, sub.execCalls.single())
    }

    @Test
    fun `execute returns the sub tool's error result unchanged`() = runTest {
        val sub = CapturingTool("boom", result = ToolExecutionResult.error("kaboom"))
        val ts = Toolset("weather", "d").apply { add(sub) }
        val r = ToolsetRegistry().apply { register(ts) }
        val out = SubToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "weather")
                put("sub_tool_name", "boom")
            },
            emptyContext(),
        )
        assertTrue(out.isError)
        assertEquals("kaboom", out.parts.text)
    }

    // ---------- Error paths through the registry ----------

    @Test
    fun `execute throws NoSuchElementException when toolset is unknown`() = runTest {
        val r = ToolsetRegistry()  // empty
        assertFailsWith<NoSuchElementException> {
            SubToolDelegate(r).execute(
                buildJsonObject {
                    put("toolset_name", "ghost")
                    put("sub_tool_name", "x")
                },
                emptyContext(),
            )
        }
    }

    @Test
    fun `execute returns ToolNotFound error when sub tool is unknown within the toolset`() = runTest {
        val r = ToolsetRegistry().apply {
            register(Toolset("weather", "d").apply { add(CapturingTool("known")) })
        }
        val out = SubToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "weather")
                put("sub_tool_name", "ghost")
            },
            emptyContext(),
        )
        assertTrue(out.isError)
        assertTrue("'ghost'" in out.parts.text, "expected missing-name in error, got: ${out.parts.text}")
        assertTrue("known" in out.parts.text, "expected available list in error, got: ${out.parts.text}")
    }

    @Test
    fun `execute routes to the right toolset when multiple are registered`() = runTest {
        val subA = CapturingTool("x", result = ToolExecutionResult.success("from-A"))
        val subB = CapturingTool("x", result = ToolExecutionResult.success("from-B"))
        val r = ToolsetRegistry().apply {
            register(Toolset("A", "dA").apply { add(subA) })
            register(Toolset("B", "dB").apply { add(subB) })
        }
        val outA = SubToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "A")
                put("sub_tool_name", "x")
            },
            emptyContext(),
        )
        val outB = SubToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "B")
                put("sub_tool_name", "x")
            },
            emptyContext(),
        )
        assertEquals("from-A", outA.parts.text)
        assertEquals("from-B", outB.parts.text)
    }
}
