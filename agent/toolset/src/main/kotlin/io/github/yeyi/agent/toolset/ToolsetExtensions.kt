package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentBuilder

/**
 * DSL — 将 [ToolsetRegistry] 中所有 Toolset 注册到 [AgentBuilder]。
 *
 * 与走 toolset 框架的其他 capability DSL（如 `mcps`）**可共存**：本 DSL 可重复调用,
 * 第二次起把注册表内容并入已安装的共享插件 —— 最终仍只有一份
 * `load_toolset` + `member_tool_delegate`,所有 Toolset 挂在同一个注册表下。
 *
 * Toolset 重名时抛 [IllegalArgumentException]（注册表层 fail-fast）。
 */
public fun AgentBuilder.toolsets(registry: ToolsetRegistry) {
    val existing = findPlugin(Toolset.CAPABILITY_TYPE) as? ToolsetPlugin
    if (existing == null) {
        plugin(ToolsetPlugin(registry))
    } else {
        existing.registry.register(registry.all())
    }
}
