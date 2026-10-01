package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.AgentPlugin
import io.github.yeyi.agent.AgentPluginContext

/**
 * TOOL level 的 LazyTool 接线模板。
 *
 * 安装 [SearchTool] 和 [ToolCaller]。
 */
internal class ToolSearchPlugin(
    private val registry: LazyToolRegistry,
    private val toolCaller: ToolCaller,
) : AgentPlugin<Unit> {

    override val id: String = "tool-search"

    override fun configure(block: Unit.() -> Unit) {
        // no-op: this plugin has no config
    }

    override fun install(context: AgentPluginContext) {
        context.registerTool(SearchTool(registry))
        context.registerTool(toolCaller)
    }
}
