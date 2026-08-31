package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.capability.CapabilityArguments
import io.github.yeyi.agent.capability.CapabilityPlugin
import io.github.yeyi.agent.tool.Tool

/**
 * Toolset 的接线模板 —— 实现全部 4 个接线方法。
 *
 * 仅供 Toolset 模块内部 `toolsets(registry, ...)` 扩展函数使用;
 * 外部调用方应直接使用扩展函数,不感知本类。
 */
internal class ToolsetPlugin(
    private val registry: ToolsetRegistry,
) : CapabilityPlugin<Toolset, Unit, ToolsetContext>() {

    override fun registry(): ToolsetRegistry = registry

    override fun contextFactory(): ToolsetContextFactory = ToolsetContextFactory()

    override fun arguments(): CapabilityArguments<Unit>? = null

    override fun auxiliaryTools(): List<Tool> = listOf(MemberToolDelegate(registry))
}
