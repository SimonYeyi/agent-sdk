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
 * 从 AgentDefinition 和 AgentWiring 构建 Agent 实例。
 *
 * @param wiring 接线配置，包含 memory 和各 registry
 * @return Agent 实例
 */
public fun AgentDefinition.spawn(wiring: AgentWiring): Agent {
    val toolsetRegistry = wiring.resolveToolsetRegistry(this@spawn)
    val (toolRegistry, lazyToolRegistry) = wiring.resolveToolRegistries(this@spawn)
    return agent {
        persona(Persona(persona))
        llmProvider(wiring.resolveLlmProvider(this@spawn))
        memory(wiring.memory, maxMemoryRounds)
        maxIterations(maxIterations)
        toolRegistry?.let { tools(it) }
        lazyToolRegistry?.let { lazyTools(it) }
        toolsetRegistry?.let { toolsets(it) }
        wiring.resolveSkillRegistry(this@spawn)?.let { skills(it) }
        wiring.resolveSubagentRegistry(this@spawn)?.let { subagents(it) }
        wiring.resolveMcpRegistry(this@spawn, toolsetRegistry)?.let { mcps(it) }
    }
}
