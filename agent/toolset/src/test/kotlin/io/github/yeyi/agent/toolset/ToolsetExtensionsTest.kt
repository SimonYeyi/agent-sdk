package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
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

    private class StubSubTool(
        override val name: String,
    ) : Tool {
        override val description: String = "stub"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
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
            r.register(Toolset(name, desc).apply { add(StubSubTool("${name}_inner")) })
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
        b.persona(Persona("x"))
        b.toolsets(registryWith("weather" to "天气查询", "news" to "新闻查询"))
        b.build().run(AgentQuery.text("hi")).toList()
        val loadToolset = llm.recorded.single().tools.single { it.name == "load_toolset" }
        assertTrue("weather" in loadToolset.description, "expected 'weather' in load_toolset description")
        assertTrue("天气查询" in loadToolset.description, "expected '天气查询' in load_toolset description")
        assertTrue("news" in loadToolset.description, "expected 'news' in load_toolset description")
        assertTrue("新闻查询" in loadToolset.description, "expected '新闻查询' in load_toolset description")
    }

    @Test
    fun `Delegate mode registers sub_tool_delegate tool`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.toolsets(registryWith("weather" to "d"))
        b.build().run(AgentQuery.text("hi")).toList()
        val names = llm.recorded.single().tools.map { it.name }
        assertTrue("sub_tool_delegate" in names, "expected sub_tool_delegate in $names")
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
    fun `Delegate mode exposes only load_toolset and sub_tool_delegate when registry has one toolset`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.toolsets(registryWith("weather" to "d"))
        b.build().run(AgentQuery.text("hi")).toList()
        val names = llm.recorded.single().tools.map { it.name }.toSet()
        assertEquals(setOf("load_toolset", "sub_tool_delegate"), names)
    }

    // ---------- OneToOne mode ----------

    @Test
    fun `OneToOne mode registers toolset_ prefix tools for each toolset`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.toolsets(
            registryWith("weather" to "d1", "news" to "d2"),
            enableDelegateAdaptMode = false,
        )
        b.build().run(AgentQuery.text("hi")).toList()
        val names = llm.recorded.single().tools.map { it.name }.toSet()
        assertTrue("toolset_weather" in names, "expected toolset_weather in $names")
        assertTrue("toolset_news" in names, "expected toolset_news in $names")
    }

    @Test
    fun `OneToOne mode does NOT register load_toolset`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.toolsets(registryWith("weather" to "d"), enableDelegateAdaptMode = false)
        b.build().run(AgentQuery.text("hi")).toList()
        val names = llm.recorded.single().tools.map { it.name }
        assertFalse("load_toolset" in names, "OneToOne mode must not register load_toolset, got: $names")
    }

    @Test
    fun `OneToOne mode still registers sub_tool_delegate`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.toolsets(registryWith("weather" to "d"), enableDelegateAdaptMode = false)
        b.build().run(AgentQuery.text("hi")).toList()
        val names = llm.recorded.single().tools.map { it.name }
        assertTrue("sub_tool_delegate" in names, "sub_tool_delegate must be registered in both modes, got: $names")
    }

    @Test
    fun `OneToOne mode toolset_ tool description matches the toolset description`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.toolsets(
            registryWith("weather" to "weather-desc"),
            enableDelegateAdaptMode = false,
        )
        b.build().run(AgentQuery.text("hi")).toList()
        val tool = llm.recorded.single().tools.single { it.name == "toolset_weather" }
        assertEquals("weather-desc", tool.description)
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
    fun `toolsets DSL called twice throws InstallException with guidance`() {
        val r1 = ToolsetRegistry()
        val r2 = ToolsetRegistry()
        val ex = assertFailsWith<io.github.yeyi.agent.capability.CapabilityInstaller.InstallException> {
            AgentBuilder().apply {
                toolsets(r1)
                toolsets(r2)
            }.build()
        }
        val msg = ex.message ?: ""
        assertTrue(
            "toolset framework" in msg && "configured more than once" in msg,
            "message should explain DSL was invoked multiple times: $msg",
        )
        assertTrue(
            "higher-level DSL" in msg && "kdoc will mention" in msg,
            "message should direct user to check kdoc of higher-level DSLs that wrap the toolset framework: $msg",
        )
        assertTrue(
            ex.cause is io.github.yeyi.agent.tool.ToolDuplicateException,
            "cause should be ToolDuplicateException (InstallException middle layer is skipped), got: ${ex.cause?.javaClass?.name}",
        )
    }
}
