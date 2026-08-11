package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentBuilder

/**
 * DSL — 将 [ToolsetRegistry] 中所有 Toolset 注册到 [AgentBuilder]。
 *
 * @param enableDelegateAdaptMode true (默认) — 单 Load Tool `load_toolset` + 共享 `sub_tool_delegate`；
 *                                false — 每个 Toolset 暴露为独立 Tool `toolset_<name>` + 共享 `sub_tool_delegate`。
 *
 * **不能重复注入** —— 本 DSL 会安装 `load_toolset` / `sub_tool_delegate`,这两个是
 * toolset 框架对外暴露的 discovery/delegation 工具,任何走 toolset 框架的 capability
 * DSL 都会安装同一对(直接 `toolsets()` 调用,或在其之上封装的更高层 DSL)。同一 Agent
 * 上只能出现一次,重复注入抛 [ToolsetsInstallException];直接调用 grep `toolsets`
 * 关键字即可找到,封装型 DSL 需要看它的 kdoc —— 走 toolset 框架的 DSL 会在 kdoc 中
 * 提及 `toolsets`。
 */
public fun AgentBuilder.toolsets(
    registry: ToolsetRegistry,
    enableDelegateAdaptMode: Boolean = true,
) {
    ToolsetFactory(registry).installOn(this, enableDelegateAdaptMode)
}