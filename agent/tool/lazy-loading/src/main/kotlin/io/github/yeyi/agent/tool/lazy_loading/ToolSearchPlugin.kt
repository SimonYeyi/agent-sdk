package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.AgentPlugin
import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.capability.CapabilityRegistry

/**
 * TOOL level 的 LazyTool 接线模板。
 *
 * 安装 [SearchTool] 和 [DynamicToolCaller]。
 *
 * @param registry TOOL level 视图（或完整 registry），由 [lazyTools] 传入
 */
internal class ToolSearchPlugin(
    private val registry: CapabilityRegistry<LazyTool, Unit, SchemaLazyContext>,
    private val dynamicToolCaller: DynamicToolCaller,
) : AgentPlugin<Unit> {

    override val id: String = "tool_search"

    override fun configure(block: Unit.() -> Unit) {
        // no-op: this plugin has no config
    }

    override fun install(context: AgentPluginContext) {
        context.registerTool(SearchTool(registry))
        context.registerTool(dynamicToolCaller)
    }
}
