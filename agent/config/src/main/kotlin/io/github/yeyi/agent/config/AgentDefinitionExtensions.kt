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
    // null 表示使用 registry 中全部项，emptyList() 表示不使用任何项
    val filteredToolRegistry = ToolRegistry()
    val filteredLazyToolRegistry = LazyToolRegistry()

    if (tools == null) {
        filteredToolRegistry.register(toolRegistry?.all() ?: emptyList())
        filteredLazyToolRegistry.register(lazyToolRegistry?.all() ?: emptyList())
    } else {
        tools.forEach { name ->
            lazyToolRegistry?.getOrNull(name)?.let {
                filteredLazyToolRegistry.register(it)
            } ?: toolRegistry?.getOrNull(name)?.let {
                filteredToolRegistry.register(it)
            } ?: log.warn("Runtime Tool not found: $name")
        }
    }

    // toolsets 配置：null = 从 toolsetRegistry 的 all() 获取所有
    val filteredToolsetRegistry = ToolsetRegistry()
    if (toolsets == null) {
        filteredToolsetRegistry.register(toolsetRegistry?.all() ?: emptyList())
    } else {
        toolsets.forEach { name ->
            toolsetRegistry?.getOrNull(name)?.let { filteredToolsetRegistry.register(it) }
                ?: log.warn("Runtime Toolset not found: $name")
        }
    }

    // skills 配置：null = 从 skillRegistry 的 all() 获取所有
    val filteredSkillRegistry = SkillRegistry()
    if (skills == null) {
        filteredSkillRegistry.register(skillRegistry?.all() ?: emptyList())
    } else {
        skills.forEach { name ->
            skillRegistry?.getOrNull(name)?.let { filteredSkillRegistry.register(it) }
                ?: log.warn("Runtime Skill not found: $name")
        }
    }

    // subagents 配置：null = 从 subagentRegistry 的 all() 获取所有
    val filteredSubagentRegistry = SubagentRegistry()
    if (subagents == null) {
        filteredSubagentRegistry.register(subagentRegistry?.all() ?: emptyList())
    } else {
        subagents.forEach { name ->
            subagentRegistry?.getOrNull(name)?.let { filteredSubagentRegistry.register(it) }
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
    // 注意：只有 registry 非 null 时才安装对应插件
    // 原因：如果传入空 registry，插件会用空快照初始化，之后动态注入也感知不到
    // 使用 ?.let 语法确保 null 时不安装插件，支持未来运行时动态注册
    return agent {
        persona(Persona(persona))
        llmProvider(llmProvider)
        memory(memory, maxMemoryRounds)
        maxIterations(maxIterations)
        toolRegistry?.let { tools(filteredToolRegistry) }
        lazyToolRegistry?.let { lazyTools(filteredLazyToolRegistry) }
        toolsetRegistry?.let { toolsets(filteredToolsetRegistry) }
        skillRegistry?.let { skills(filteredSkillRegistry) }
        subagentRegistry?.let { subagents(filteredSubagentRegistry) }
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
