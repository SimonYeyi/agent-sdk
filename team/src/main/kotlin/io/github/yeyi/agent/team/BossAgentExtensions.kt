package io.github.yeyi.agent.team

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.config.AgentDefinition
import io.github.yeyi.agent.config.AgentScope

/**
 * 从 AgentDefinition 构建 BossAgent。
 *
 * @param scope 运行时上下文
 * @return BossAgent 实例
 */
public fun AgentDefinition.spawnBoss(scope: AgentScope): BossAgent {
    val registries = scope.resolver().resolve(this@spawnBoss)
    return bossAgent {
        memory(scope.memory, maxMemoryRounds)
        llmProvider(registries.llmProvider)
        maxIterations(maxIterations)
        persona(Persona(persona))
        registries.toolRegistry?.let { tools(it) }
        registries.lazyToolRegistry?.let { lazyTools(it) }
        registries.toolsetRegistry?.let { toolsets(it) }
        registries.skillRegistry?.let { skills(it) }
        registries.subagentRegistry?.let { subagents(it) }
        registries.mcpRegistry?.let { mcps(it) }
    }
}
