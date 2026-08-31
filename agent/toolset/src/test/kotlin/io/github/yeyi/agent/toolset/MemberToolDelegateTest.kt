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

class MemberToolDelegateTest {

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
            error("LlmProvider.chat must not be called in MemberToolDelegateTest")
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
        val captured = CapturingTool("inner")
        val ts = Toolset("weather", "d").apply { add(captured) }
        val r = ToolsetRegistry().apply { register(ts) }
        return r to captured
    }

    // ---------- Tool identity & schema ----------

    @Test
    fun `name is member_tool_delegate`() {
        val (r, _) = buildRegistry()
        assertEquals("member_tool_delegate", MemberToolDelegate(r).name)
    }

    @Test
    fun `schema declares toolset_name, tool_name, tool_arguments with required keys`() {
        val (r, _) = buildRegistry()
        val schema = MemberToolDelegate(r).parametersSchema
        val parsed = when (schema) {
            is ToolParameters.JsonSchema -> Json.parseToJsonElement(schema.schema) as JsonObject
            ToolParameters.Empty -> error("expected JsonSchema, got Empty")
        }
        val props = parsed["properties"] as JsonObject
        assertTrue("toolset_name" in props)
        assertTrue("tool_name" in props)
        assertTrue("tool_arguments" in props)
        val required = (parsed["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
        assertTrue("toolset_name" in required)
        assertTrue("tool_name" in required)
    }

    // ---------- Validation errors ----------

    @Test
    fun `execute returns error when toolset_name is missing`() = runTest {
        val (r, _) = buildRegistry()
        val out = MemberToolDelegate(r).execute(
            buildJsonObject {
                put("tool_name", "inner")
            },
            emptyContext(),
        )
        assertTrue(out.isError)
        assertTrue("toolset_name" in out.parts.text, "expected error to mention 'toolset_name', got: ${out.parts.text}")
    }

    @Test
    fun `execute returns error when tool_name is missing`() = runTest {
        val (r, _) = buildRegistry()
        val out = MemberToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "weather")
            },
            emptyContext(),
        )
        assertTrue(out.isError)
        assertTrue("tool_name" in out.parts.text, "expected error to mention 'tool_name', got: ${out.parts.text}")
    }

    @Test
    fun `execute returns error when both required fields are missing`() = runTest {
        val (r, _) = buildRegistry()
        val out = MemberToolDelegate(r).execute(JsonObject(emptyMap()), emptyContext())
        assertTrue(out.isError)
    }

    // ---------- Routing ----------

    @Test
    fun `execute dispatches to the named tool and returns its result`() = runTest {
        val (r, captured) = buildRegistry()
        val out = MemberToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "weather")
                put("tool_name", "inner")
            },
            emptyContext(),
        )
        assertFalse(out.isError)
        assertEquals("ok", out.parts.text)
        assertEquals(1, captured.execCalls.size, "tool should have been invoked exactly once")
    }

    @Test
    fun `execute forwards tool_arguments as the tool's arguments`() = runTest {
        val (r, captured) = buildRegistry()
        val args = buildJsonObject { put("city", JsonPrimitive("Beijing")) }
        MemberToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "weather")
                put("tool_name", "inner")
                put("tool_arguments", args)
            },
            emptyContext(),
        )
        assertEquals(args, captured.execCalls.single())
    }

    @Test
    fun `execute uses JsonNull when tool_arguments is absent`() = runTest {
        val (r, captured) = buildRegistry()
        MemberToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "weather")
                put("tool_name", "inner")
            },
            emptyContext(),
        )
        assertEquals(JsonNull, captured.execCalls.single())
    }

    @Test
    fun `execute returns the tool's error result unchanged`() = runTest {
        val captured = CapturingTool("boom", result = ToolExecutionResult.error("kaboom"))
        val ts = Toolset("weather", "d").apply { add(captured) }
        val r = ToolsetRegistry().apply { register(ts) }
        val out = MemberToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "weather")
                put("tool_name", "boom")
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
            MemberToolDelegate(r).execute(
                buildJsonObject {
                    put("toolset_name", "ghost")
                    put("tool_name", "x")
                },
                emptyContext(),
            )
        }
    }

    @Test
    fun `execute returns ToolNotFound error when tool is unknown within the toolset`() = runTest {
        val r = ToolsetRegistry().apply {
            register(Toolset("weather", "d").apply { add(CapturingTool("known")) })
        }
        val out = MemberToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "weather")
                put("tool_name", "ghost")
            },
            emptyContext(),
        )
        assertTrue(out.isError)
        assertTrue("'ghost'" in out.parts.text, "expected missing-name in error, got: ${out.parts.text}")
        assertTrue("known" in out.parts.text, "expected available list in error, got: ${out.parts.text}")
    }

    @Test
    fun `execute routes to the right toolset when multiple are registered`() = runTest {
        val capturedA = CapturingTool("x", result = ToolExecutionResult.success("from-A"))
        val capturedB = CapturingTool("x", result = ToolExecutionResult.success("from-B"))
        val r = ToolsetRegistry().apply {
            register(Toolset("A", "dA").apply { add(capturedA) })
            register(Toolset("B", "dB").apply { add(capturedB) })
        }
        val outA = MemberToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "A")
                put("tool_name", "x")
            },
            emptyContext(),
        )
        val outB = MemberToolDelegate(r).execute(
            buildJsonObject {
                put("toolset_name", "B")
                put("tool_name", "x")
            },
            emptyContext(),
        )
        assertEquals("from-A", outA.parts.text)
        assertEquals("from-B", outB.parts.text)
    }
}
