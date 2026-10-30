package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LazyToolExtensionsTest {

    private class FixedTool(override val name: String, override val description: String = "test") : Tool {
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("ok")
    }

    private val mockPluginContext = object : io.github.yeyi.agent.AgentPluginContext {
        private val tools = mutableListOf<Tool>()
        override fun registerTool(tool: Tool) {
            tools.add(tool)
        }
        override fun appendInstructions(content: String) {}

        fun getInstalledTools(): List<Tool> = tools.toList()
    }

    @Test
    fun `lazyTools routes SCHEMA level to SchemaLazyPlugin`() {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询"), LazyTool.Level.SCHEMA))
        registry.register(LazyTool(FixedTool("news", "新闻查询"), LazyTool.Level.SCHEMA))

        val dynamicToolCaller = DynamicToolCaller(registry)
        val plugin = SchemaLazyPlugin(registry, dynamicToolCaller)
        plugin.install(mockPluginContext)

        val toolNames = mockPluginContext.getInstalledTools().map { it.name }
        assertTrue("load_lazy_tool" in toolNames, "load_lazy_tool should be installed")
        assertTrue(DynamicToolCaller.NAME in toolNames, "tool_caller should be installed")
    }

    @Test
    fun `lazyTools routes TOOL level to ToolSearchPlugin`() {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询"), LazyTool.Level.TOOL))
        registry.register(LazyTool(FixedTool("news", "新闻查询"), LazyTool.Level.TOOL))

        val dynamicToolCaller = DynamicToolCaller(registry)
        val plugin = ToolSearchPlugin(registry, dynamicToolCaller)
        plugin.install(mockPluginContext)

        val toolNames = mockPluginContext.getInstalledTools().map { it.name }
        assertTrue("search_tool" in toolNames, "search_tool should be installed")
        assertTrue(DynamicToolCaller.NAME in toolNames, "tool_caller should be installed")
    }

    @Test
    fun `TOOL level search_tool returns matched tools via execute`() = runTest {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询"), LazyTool.Level.TOOL))
        registry.register(LazyTool(FixedTool("news", "新闻查询"), LazyTool.Level.TOOL))

        val searchTool = SearchTool(registry)

        val fakeLlmProvider = object : io.github.yeyi.agent.llm.LlmProvider {
            override val name: String = "fake"
            override suspend fun chat(request: io.github.yeyi.agent.llm.ChatRequest) =
                io.github.yeyi.agent.llm.ChatResponse(
                    message = io.github.yeyi.agent.llm.ChatMessage.Assistant(content = """{"matched_tools": ["weather"]}""", toolCalls = emptyList()),
                    usage = null,
                    finishReason = io.github.yeyi.agent.llm.FinishReason.Stop,
                )
            override fun chatStream(request: io.github.yeyi.agent.llm.ChatRequest) = error("not implemented")
        }

        val result = searchTool.execute(
            arguments = buildJsonObject { put("queries", buildJsonArray { add(JsonPrimitive("天气")) }) },
            context = createStubContext(fakeLlmProvider)
        )

        assertTrue(!result.isError)
        val text = result.parts.text
        assertTrue(text.contains("weather"))
        assertTrue(text.contains("天气查询"))
        assertTrue(text.contains("查询到以下工具"))
        assertTrue("news" !in text, "should NOT contain news: $text")
    }

    @Test
    fun `SCHEMA level tool_caller resolves target via registry`() {
        val registry = LazyToolRegistry()
        val weatherTool = FixedTool("weather", "天气查询")
        registry.register(LazyTool(weatherTool, LazyTool.Level.SCHEMA))

        val dynamicToolCaller = DynamicToolCaller(registry)

        val result = dynamicToolCaller.resolveTarget(
            buildJsonObject {
                put("tool_name", "weather")
                put("arguments", buildJsonObject { })
            }
        )

        assertEquals(weatherTool, result.tool)
    }

    @Test
    fun `TOOL level tool_caller resolves target via registry`() {
        val registry = LazyToolRegistry()
        val weatherTool = FixedTool("weather", "天气查询")
        registry.register(LazyTool(weatherTool, LazyTool.Level.TOOL))

        val dynamicToolCaller = DynamicToolCaller(registry)

        val result = dynamicToolCaller.resolveTarget(
            buildJsonObject {
                put("tool_name", "weather")
                put("arguments", buildJsonObject { })
            }
        )

        assertEquals(weatherTool, result.tool)
    }

    @Test
    fun `toolCaller shares same registry for both levels`() {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询"), LazyTool.Level.SCHEMA))
        registry.register(LazyTool(FixedTool("news", "新闻查询"), LazyTool.Level.TOOL))

        val dynamicToolCaller = DynamicToolCaller(registry)

        val weatherResult = dynamicToolCaller.resolveTarget(
            buildJsonObject {
                put("tool_name", "weather")
                put("arguments", buildJsonObject { })
            }
        )
        val newsResult = dynamicToolCaller.resolveTarget(
            buildJsonObject {
                put("tool_name", "news")
                put("arguments", buildJsonObject { })
            }
        )

        assertEquals("weather", weatherResult.tool.name)
        assertEquals("news", newsResult.tool.name)
    }

    private fun createStubContext(llmProvider: io.github.yeyi.agent.llm.LlmProvider? = null): ToolExecutionContext {
        val provider = llmProvider ?: object : io.github.yeyi.agent.llm.LlmProvider {
            override val name: String = "test"
            override suspend fun chat(request: io.github.yeyi.agent.llm.ChatRequest) = error("not implemented")
            override fun chatStream(request: io.github.yeyi.agent.llm.ChatRequest) = error("not implemented")
        }
        return ToolExecutionContext(
            toolCallId = "test",
            agentContext = io.github.yeyi.agent.AgentContext(
                persona = io.github.yeyi.agent.Persona("test"),
                maxIterations = 10,
                currentIteration = 1,
                memory = io.github.yeyi.agent.memory.InMemoryMemory(),
                llmProvider = provider,
                tools = emptyList(),
                maxRounds = 20,
            )
        )
    }
}
