package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.capability.CapabilityArguments
import io.github.yeyi.agent.capability.CapabilityPlugin
import io.github.yeyi.agent.tool.Tool

/**
 * SCHEMA level 的 LazyTool 接线模板。
 *
 * 内部使用 [CapabilityPlugin] 架构，安装 `load_lazy_tool` 工具。
 */
internal class SchemaLazyPlugin(
    registry: LazyToolRegistry,
    private val toolCaller: ToolCaller,
) : CapabilityPlugin<LazyTool, Unit, LazyToolContext>(registry, true) {

    override fun contextFactory(): LazyToolContextFactory = LazyToolContextFactory()

    override fun arguments(): CapabilityArguments<Unit>? = null

    override fun auxiliaryTools(): List<Tool> = listOf(toolCaller)
}
