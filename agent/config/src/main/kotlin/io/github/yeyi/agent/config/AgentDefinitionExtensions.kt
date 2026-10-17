package io.github.yeyi.agent.config

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.mcp.ClientInfo
import io.github.yeyi.agent.mcp.Mcp
import io.github.yeyi.agent.mcp.McpClient
import io.github.yeyi.agent.mcp.McpRegistry
import io.github.yeyi.agent.mcp.StdioTransport
import io.github.yeyi.agent.mcp.SseTransport
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.providers.anthropic.AnthropicProvider
import io.github.yeyi.agent.providers.openai.OpenAiProvider
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.skill.skills
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.subagent.subagents
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.tool.lazy_loading.LazyToolRegistry
import io.github.yeyi.agent.tool.lazy_loading.lazyTools
import io.github.yeyi.agent.toolset.ToolsetRegistry
import io.github.yeyi.agent.toolset.toolsets

public fun AgentDefinition.spawn(
    memory: Memory,
    toolRegistry: ToolRegistry? = null,
    lazyToolRegistry: LazyToolRegistry? = null,
    toolsetRegistry: ToolsetRegistry? = null,
    skillRegistry: SkillRegistry? = null,
    subagentRegistry: SubagentRegistry? = null
): Agent {
    // 1. 从各 registry 中过滤出声明的项
    // tools 列表优先从 lazyToolRegistry 查找，未找到则从 toolRegistry 查找
    val filteredToolRegistry = ToolRegistry()
    val filteredLazyToolRegistry = LazyToolRegistry()
    tools.forEach { name ->
        lazyToolRegistry?.getOrNull(name)?.let {
            filteredLazyToolRegistry.register(it)
        } ?: toolRegistry?.getOrNull(name)?.let {
            filteredToolRegistry.register(it)
        } ?: log.warn("Runtime Tool not found: $name")
    }

    val filteredToolsetRegistry = ToolsetRegistry().apply {
        toolsets.forEach { name ->
            toolsetRegistry?.getOrNull(name)?.let { register(it) }
                ?: log.warn("Runtime Toolset not found: $name")
        }
    }

    val filteredSkillRegistry = SkillRegistry().apply {
        skills.forEach { name ->
            skillRegistry?.getOrNull(name)?.let { register(it) }
                ?: log.warn("Runtime Skill not found: $name")
        }
    }

    val filteredSubagentRegistry = SubagentRegistry().apply {
        subagents.forEach { name ->
            subagentRegistry?.getOrNull(name)?.let { register(it) }
                ?: log.warn("Runtime Subagent not found: $name")
        }
    }

    // 2. 创建 MCP Registry 并注册声明的 MCP
    val mcpRegistry = McpRegistry(
        toolsetRegistry = filteredToolsetRegistry,
        clientInfo = ClientInfo("agent", version.toString())
    )
    mcps.forEach { mcpConfig ->
        val mcp = createMcp(mcpConfig)
        mcpRegistry.register(mcp)
    }

    // 3. 构建 LLM Provider
    val llmProvider = createLlmProvider(provider)

    // 4. 构建 Agent
    return agent {
        persona(Persona(persona))
        llmProvider(llmProvider)
        memory(memory, maxMemoryRounds)
        maxIterations(maxIterations)
        tools(filteredToolRegistry)
        lazyTools(filteredLazyToolRegistry)
        toolsets(filteredToolsetRegistry)
        skills(filteredSkillRegistry)
        subagents(filteredSubagentRegistry)
    }
}

private fun createLlmProvider(provider: AgentDefinition.Provider): LlmProvider {
    return when (provider.name) {
        "anthropic" -> AnthropicProvider(
            baseUrl = provider.baseUrl,
            model = provider.model,
            apiKey = provider.apiKey
        )
        "openai" -> OpenAiProvider(
            baseUrl = provider.baseUrl,
            model = provider.model,
            apiKey = provider.apiKey
        )
        else -> error("Unknown provider: ${provider.name}")
    }
}

private fun createMcp(config: AgentDefinition.Mcp): Mcp {
    return object : Mcp() {
        override val name: String = config.name
        override val description: String = config.description
        override val client: McpClient = McpClient(
            when (val t = config.transport) {
                is AgentDefinition.Mcp.Transport.Stdio -> StdioTransport(
                    command = listOf(t.command) + t.args,
                    workingDirectory = null
                )
                is AgentDefinition.Mcp.Transport.Http -> SseTransport(
                    endpoint = t.url,
                    httpHeaders = t.headers
                )
            }
        )
    }
}
