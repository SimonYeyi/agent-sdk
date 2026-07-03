package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.capability.CapabilityAdapter

/**
 * DSL — 将 [ToolsetRegistry] 中所有 Toolset 注册到 [AgentBuilder]。
 *
 * @param enableDelegateAdaptMode true (默认) — 单 Load Tool `load_toolset` + 共享 `sub_tool_delegate`；
 *                                false — 每个 Toolset 暴露为独立 Tool `toolset_<name>` + 共享 `sub_tool_delegate`。
 */
public fun AgentBuilder.toolsets(
    registry: ToolsetRegistry,
    enableDelegateAdaptMode: Boolean = true,
) {
    CapabilityAdapter.of(
        registry,
        ToolsetContextFactory(),
        null,
        enableDelegateAdaptMode
    ).installOn(this)
    tool(SubToolDelegate(registry))
}
