package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.capability.CapabilityPlugin
import io.github.yeyi.agent.tool.Tool

/**
 * Toolset 的接线模板 —— 统一委托模式（单一 `load_toolset` + 共享 `member_tool_delegate`）。
 *
 * 仅供 Toolset 模块内部 `toolsets(registry)` 扩展函数使用;
 * 外部调用方应直接使用扩展函数,不感知本类。
 *
 * [registry] 为 internal val —— 供本模块的合并逻辑把后续注册表内容并入共享插件。
 */
internal class ToolsetPlugin(internal val registry: ToolsetRegistry) :
    CapabilityPlugin<Toolset, Unit, ToolsetContext>(registry, enableDelegateAdaptMode = true) {

    override fun contextFactory(): ToolsetContextFactory = ToolsetContextFactory()

    override fun auxiliaryTools(): List<Tool> = listOf(MemberToolDelegate(registry))
}
