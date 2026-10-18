package io.github.yeyi.agent.team

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.config.AgentDefinition
import io.github.yeyi.agent.config.AgentScope

/**
 * 从 AgentDefinition 构建 BossAgent。
 *
 * @param scope 运行时上下文，包含 memory 和 wiring
 * @return BossAgent 实例
 */
public fun AgentDefinition.spawnBoss(scope: AgentScope): BossAgent {
    val toolsetRegistry = scope.resolveToolsetRegistry(this@spawnBoss)
    val (toolRegistry, lazyToolRegistry) = scope.resolveToolRegistries(this@spawnBoss)
    return bossAgent {
        memory(scope.memory, maxMemoryRounds)
        llmProvider(scope.resolveLlmProvider(this@spawnBoss))
        maxIterations(maxIterations)
        persona(Persona(persona))
        toolRegistry?.let { tools(it) }
        lazyToolRegistry?.let { lazyTools(it) }
        toolsetRegistry?.let { toolsets(it) }
        scope.resolveSkillRegistry(this@spawnBoss)?.let { skills(it) }
        scope.resolveSubagentRegistry(this@spawnBoss)?.let { subagents(it) }
        scope.resolveMcpRegistry(this@spawnBoss, toolsetRegistry)?.let { mcps(it) }
    }
}
