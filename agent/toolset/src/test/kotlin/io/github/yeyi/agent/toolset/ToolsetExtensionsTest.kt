package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolsetExtensionsTest {

    private class StubMemberTool(
        override val name: String,
    ) : Tool {
        override val description: String = "stub"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("ok")
    }

    /** A minimal LlmProvider that records every request and returns a stop response. */
    private class RecordingLlm : LlmProvider {
        override val name: String = "recording"
        val recorded: MutableList<ChatRequest> = mutableListOf()

        override suspend fun chat(request: ChatRequest): ChatResponse {
            recorded += request
            return ChatResponse(
                message = ChatMessage.Assistant(content = "ok"),
                finishReason = FinishReason.Stop,
            )
        }

        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow {
            recorded += request
            emit(
                ChatResponseEvent.Done(
                    usage = null,
                    finishReason = FinishReason.Stop,
                )
            )
        }
    }

    private fun registryWith(
        vararg toolsets: Pair<String, String>,
    ): ToolsetRegistry {
        val r = ToolsetRegistry()
        toolsets.forEach { (name, desc) ->
            r.register(Toolset(name, desc).apply { add(StubMemberTool("${name}_inner")) })
        }
        return r
    }

    // ---------- Delegate mode (default) ----------

    @Test
    fun `Delegate mode registers load_toolset tool visible to the LLM`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.toolsets(registryWith("weather" to "天气查询"))
        b.build().run(AgentQuery.text("hi")).toList()
        val names = llm.recorded.single().tools.map { it.name }
        assertTrue("load_toolset" in names, "expected load_toolset in $names")
    }

    @Test
    fun `Delegate mode load_toolset description lists all toolsets by name and description`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.persona(AgentPersona("x"))
        b.toolsets(registryWith("weather" to "天气查询", "news" to "新闻查询"))
        b.build().run(AgentQuery.text("hi")).toList()
        val loadToolset = llm.recorded.single().tools.single { it.name == "load_toolset" }
        assertTrue("weather" in loadToolset.description, "expected 'weather' in load_toolset description")
        assertTrue("天气查询" in loadToolset.description, "expected '天气查询' in load_toolset description")
        assertTrue("news" in loadToolset.description, "expected 'news' in load_toolset description")
        assertTrue("新闻查询" in loadToolset.description, "expected '新闻查询' in load_toolset description")
    }

    @Test
    fun `Delegate mode registers member_tool_delegate tool`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.toolsets(registryWith("weather" to "d"))
        b.build().run(AgentQuery.text("hi")).toList()
        val names = llm.recorded.single().tools.map { it.name }
        assertTrue("member_tool_delegate" in names, "expected member_tool_delegate in $names")
    }

    @Test
    fun `Delegate mode does NOT register toolset_ prefix tools`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.toolsets(registryWith("weather" to "d"))
        b.build().run(AgentQuery.text("hi")).toList()
        val names = llm.recorded.single().tools.map { it.name }
        assertFalse(
            names.any { it.startsWith("toolset_") },
            "Delegate mode must not register toolset_<name> tools, got: $names"
        )
    }

    @Test
    fun `Delegate mode exposes only load_toolset and member_tool_delegate when registry has one toolset`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.toolsets(registryWith("weather" to "d"))
        b.build().run(AgentQuery.text("hi")).toList()
        val names = llm.recorded.single().tools.map { it.name }.toSet()
        assertEquals(setOf("load_toolset", "member_tool_delegate"), names)
    }

    // ---------- Misc ----------

    @Test
    fun `toolsets DSL rejects a toolset with a duplicate name at the registry layer`() {
        val r = ToolsetRegistry()
        r.register(Toolset("dup", "d"))
        assertFailsWith<IllegalArgumentException> {
            r.register(Toolset("dup", "d2"))
        }
    }

    @Test
    fun `toolsets called twice merges into one shared plugin`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.toolsets(registryWith("weather" to "d1"))
        b.toolsets(registryWith("news" to "d2"))
        b.build().run(AgentQuery.text("hi")).toList()

        val names = llm.recorded.single().tools.map { it.name }
        assertEquals(1, names.count { it == "load_toolset" }, "expected exactly one load_toolset, got: $names")
        assertEquals(1, names.count { it == "member_tool_delegate" }, "expected exactly one member_tool_delegate, got: $names")
        val loadToolset = llm.recorded.single().tools.single { it.name == "load_toolset" }
        assertTrue("weather" in loadToolset.description, "merged toolset 'weather' should be visible: ${loadToolset.description}")
        assertTrue("news" in loadToolset.description, "merged toolset 'news' should be visible: ${loadToolset.description}")
    }

    @Test
    fun `toolsets called twice with duplicate toolset name throws IllegalArgumentException`() = runTest {
        val b = AgentBuilder().apply { llmProvider(RecordingLlm()) }
        b.toolsets(registryWith("weather" to "d1"))
        assertFailsWith<IllegalArgumentException> {
            b.toolsets(registryWith("weather" to "d2"))
        }
    }
}
