package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.log.LoggingTagged

/**
 * 将 SubagentRegistry 注册到 Agent。
 *
 * @param registry Subagent 注册中心
 */
public fun AgentBuilder.subagents(registry: SubagentRegistry) {
    plugin(SubagentPlugin(registry))
}

internal val log = LoggingTagged("subagent")