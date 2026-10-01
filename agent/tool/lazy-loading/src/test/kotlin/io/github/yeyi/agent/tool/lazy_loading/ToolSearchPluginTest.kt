package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolSearchPluginTest {

    private class FixedTool(override val name: String, override val description: String = "test") : Tool {
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("ok")
    }

    @Test
    fun `plugin installs search_tool and tool_caller`() {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询"), LazyTool.Level.TOOL))
        registry.register(LazyTool(FixedTool("news", "新闻查询"), LazyTool.Level.TOOL))

        val toolCaller = ToolCaller(registry)

        var installedTools: List<Tool> = emptyList()
        val context = object : AgentPluginContext {
            override fun registerTool(tool: Tool) {
                installedTools = installedTools + tool
            }
            override fun appendPersona(label: String, content: String) {}
        }

        val plugin = ToolSearchPlugin(registry, toolCaller)
        plugin.install(context)

        val toolNames = installedTools.map { it.name }
        assertEquals(2, toolNames.size, "Expected 2 tools but got: $toolNames")
        assertTrue("search_tool" in toolNames, "search_tool not found in $toolNames")
        assertTrue("tool_caller" in toolNames, "tool_caller not found in $toolNames")
    }

    @Test
    fun `search_tool has meaningful description`() {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询"), LazyTool.Level.TOOL))

        val toolCaller = ToolCaller(registry)

        var installedTools: List<Tool> = emptyList()
        val context = object : AgentPluginContext {
            override fun registerTool(tool: Tool) {
                installedTools = installedTools + tool
            }
            override fun appendPersona(label: String, content: String) {}
        }

        val plugin = ToolSearchPlugin(registry, toolCaller)
        plugin.install(context)

        val searchTool = installedTools.find { it.name == "search_tool" }!!
        assertTrue(searchTool.description.isNotEmpty(), "search_tool description should not be empty")
        assertTrue(searchTool.description.contains("工具"), "search_tool description should mention tools")
    }

    @Test
    fun `tool_caller is the same instance for TOOL level`() {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询"), LazyTool.Level.TOOL))

        val toolCaller = ToolCaller(registry)

        var installedTools: List<Tool> = emptyList()
        val context = object : AgentPluginContext {
            override fun registerTool(tool: Tool) {
                installedTools = installedTools + tool
            }
            override fun appendPersona(label: String, content: String) {}
        }

        val plugin = ToolSearchPlugin(registry, toolCaller)
        plugin.install(context)

        val toolCallerInstance = installedTools.find { it.name == "tool_caller" }
        assertTrue(toolCallerInstance === toolCaller, "tool_caller should be the same instance")
    }
}
