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
    val toolsetRegistry = scope.resolveToolsetRegistry(this@spawn)
    val (toolRegistry, lazyToolRegistry) = scope.resolveToolRegistries(this@spawn)
    return agent {
        persona(Persona(persona))
        llmProvider(scope.resolveLlmProvider(this@spawn))
        memory(scope.memory, maxMemoryRounds)
        maxIterations(maxIterations)
        toolRegistry?.let { tools(it) }
        lazyToolRegistry?.let { lazyTools(it) }
        toolsetRegistry?.let { toolsets(it) }
        scope.resolveSkillRegistry(this@spawn)?.let { skills(it) }
        scope.resolveSubagentRegistry(this@spawn)?.let { subagents(it) }
        scope.resolveMcpRegistry(this@spawn, toolsetRegistry)?.let { mcps(it) }
    }
}
