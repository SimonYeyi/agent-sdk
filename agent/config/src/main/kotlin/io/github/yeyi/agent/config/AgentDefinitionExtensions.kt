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
    val resolver = scope.resolver()
    val toolsetRegistry = resolver.resolveToolsets(this@spawn)
    val (toolRegistry, lazyToolRegistry) = resolver.resolveTools(this@spawn)
    return agent {
        persona(Persona(persona))
        llmProvider(resolver.resolveProvider(this@spawn))
        memory(scope.memory, maxMemoryRounds)
        maxIterations(maxIterations)
        toolRegistry?.let { tools(it) }
        lazyToolRegistry?.let { lazyTools(it) }
        toolsetRegistry?.let { toolsets(it) }
        resolver.resolveSkills(this@spawn)?.let { skills(it) }
        resolver.resolveSubagents(this@spawn)?.let { subagents(it) }
        resolver.resolveMcps(this@spawn, toolsetRegistry)?.let { mcps(it) }
    }
}
