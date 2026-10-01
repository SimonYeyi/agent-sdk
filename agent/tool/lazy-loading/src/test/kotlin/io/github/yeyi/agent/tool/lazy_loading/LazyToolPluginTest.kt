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

class LazyToolPluginTest {

    private class FixedTool(override val name: String, override val description: String = "test") : Tool {
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("ok")
    }

    @Test
    fun `plugin installs load_lazy_tool and lazytool_caller`() {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询")))
        registry.register(LazyTool(FixedTool("news", "新闻查询")))

        var installedTools: List<Tool> = emptyList()
        val context = object : AgentPluginContext {
            override fun registerTool(tool: Tool) {
                installedTools = installedTools + tool
            }
            override fun appendPersona(label: String, content: String) {}
        }

        val plugin = LazyToolPlugin(registry)
        plugin.install(context)

        val toolNames = installedTools.map { it.name }
        assertEquals(2, toolNames.size, "Expected 2 tools but got: $toolNames")
        assertTrue("load_lazy_tool" in toolNames, "load_lazy_tool not found in $toolNames")
        assertTrue("lazy_tool_caller" in toolNames, "lazy_tool_caller not found in $toolNames")
    }

    @Test
    fun `load_lazy_tool description lists all lazy tools`() {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(FixedTool("weather", "天气查询")))
        registry.register(LazyTool(FixedTool("news", "新闻查询")))

        var installedTools: List<Tool> = emptyList()
        val context = object : AgentPluginContext {
            override fun registerTool(tool: Tool) {
                installedTools = installedTools + tool
            }
            override fun appendPersona(label: String, content: String) {}
        }

        val plugin = LazyToolPlugin(registry)
        plugin.install(context)

        val loadTool = installedTools.find { it.name == "load_lazy_tool" }!!
        assertTrue(loadTool.description.contains("weather"))
        assertTrue(loadTool.description.contains("天气查询"))
        assertTrue(loadTool.description.contains("news"))
        assertTrue(loadTool.description.contains("新闻查询"))
    }
}
