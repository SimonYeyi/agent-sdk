package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchToolTest {

    private class FixedTool(
        override val name: String,
        override val description: String = "test tool",
    ) : Tool {
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("ok")
    }

    @Test
    fun `search_tool returns matched tools by description`() = runTest {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询")))
        registry.register(LazyTool(FixedTool("news", "新闻查询")))
        registry.register(LazyTool(FixedTool("map", "地图导航")))

        val searchTool = SearchTool(registry)

        val result = searchTool.execute(
            arguments = buildJsonObject { put("query", "天气") },
            context = createStubContext()
        )

        assertEquals(false, result.isError)
        val text = result.parts.text
        assertTrue(text.contains("weather"))
        assertTrue(text.contains("天气查询"))
        assertTrue(text.contains("search_tool 返回以下工具"))
        assertTrue(text.contains("tool_caller"))
    }

    @Test
    fun `search_tool returns matched tools by name`() = runTest {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询")))
        registry.register(LazyTool(FixedTool("news", "新闻查询")))

        val searchTool = SearchTool(registry)

        val result = searchTool.execute(
            arguments = buildJsonObject { put("query", "news") },
            context = createStubContext()
        )

        assertEquals(false, result.isError)
        val text = result.parts.text
        assertTrue(text.contains("news"))
        assertTrue(text.contains("新闻查询"))
    }

    @Test
    fun `search_tool returns empty when no match`() = runTest {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询")))

        val searchTool = SearchTool(registry)

        val result = searchTool.execute(
            arguments = buildJsonObject { put("query", "不存在的工具") },
            context = createStubContext()
        )

        assertEquals(false, result.isError)
        val text = result.parts.text
        assertTrue(text.contains("search_tool 返回以下工具"))
    }

    @Test
    fun `search_tool name is search_tool`() {
        val registry = LazyToolRegistry()
        val searchTool = SearchTool(registry)
        assertEquals("search_tool", searchTool.name)
    }

    @Test
    fun `search_tool description is empty`() {
        val registry = LazyToolRegistry()
        val searchTool = SearchTool(registry)
        assertEquals("", searchTool.description)
    }

    private fun createStubContext(): ToolExecutionContext {
        return ToolExecutionContext(
            toolCallId = "test",
            agentContext = io.github.yeyi.agent.AgentContext(
                persona = io.github.yeyi.agent.Persona("test"),
                maxIterations = 10,
                currentIteration = 1,
                memory = io.github.yeyi.agent.memory.InMemoryMemory(),
                llmProvider = object : io.github.yeyi.agent.llm.LlmProvider {
                    override val name: String = "test"
                    override suspend fun chat(request: io.github.yeyi.agent.llm.ChatRequest) = error("not implemented")
                    override fun chatStream(request: io.github.yeyi.agent.llm.ChatRequest) = error("not implemented")
                },
                tools = emptyList(),
                maxRounds = 20,
            )
        )
    }
}
