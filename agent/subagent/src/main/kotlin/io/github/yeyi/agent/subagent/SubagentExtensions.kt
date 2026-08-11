package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.log.LoggingTagged

/**
 * 将 SubagentRegistry 注册到 Agent。
 *
 * @param registry Subagent 注册中心
 * @param enableDelegateAdaptMode true 使用委托模式，false 为每个 subagent 生成独立工具
 */
public fun AgentBuilder.subagents(
    registry: SubagentRegistry,
    enableDelegateAdaptMode: Boolean = true,
) {
    SubagentInstaller(registry).installOn(this, enableDelegateAdaptMode)
}

internal val log = LoggingTagged("subagent")