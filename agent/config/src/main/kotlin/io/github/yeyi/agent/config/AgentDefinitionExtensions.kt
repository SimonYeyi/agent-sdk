package io.github.yeyi.agent.config

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.mcp.mcps
import io.github.yeyi.agent.skill.skills
import io.github.yeyi.agent.subagent.subagents
import io.github.yeyi.agent.tool.lazy_loading.lazyTools
import io.github.yeyi.agent.toolset.toolsets

/**
 * 从 AgentDefinition 和 AgentScope 构建 Agent 实例。
 *
 * @param scope 运行时上下文
 * @return Agent 实例
 */
public fun AgentDefinition.spawn(scope: AgentScope): Agent {
    val registries = scope.resolver().resolve(this@spawn)
    return agent {
        persona(Persona(persona))
        llmProvider(registries.llmProvider)
        memory(scope.memory, maxMemoryRounds)
        maxIterations(maxIterations)
        registries.toolRegistry?.let { tools(it) }
        registries.lazyToolRegistry?.let { lazyTools(it) }
        registries.toolsetRegistry?.let { toolsets(it) }
        registries.skillRegistry?.let { skills(it) }
        registries.subagentRegistry?.let { subagents(it) }
        registries.mcpRegistry?.let { mcps(it) }
    }
}
