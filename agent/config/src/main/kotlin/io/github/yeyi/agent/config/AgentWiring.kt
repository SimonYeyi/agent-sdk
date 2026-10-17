package io.github.yeyi.agent.config

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
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.tool.lazy_loading.LazyToolRegistry
import io.github.yeyi.agent.toolset.ToolsetRegistry

/**
 * Agent 接线配置，封装运行时所需的 registries。
 *
 * @param memory 会话记忆（必需）
 * @param toolRegistry 工具注册表，null 表示不使用
 * @param lazyToolRegistry 延迟加载工具注册表，null 表示不使用
 * @param toolsetRegistry 工具集注册表，null 表示不使用
 * @param skillRegistry 技能注册表，null 表示不使用
 * @param subagentRegistry 子代理注册表，null 表示不使用
 */
public data class AgentWiring(
    public val memory: Memory,
    private val toolRegistry: ToolRegistry? = null,
    private val lazyToolRegistry: LazyToolRegistry? = null,
    private val toolsetRegistry: ToolsetRegistry? = null,
    private val skillRegistry: SkillRegistry? = null,
    private val subagentRegistry: SubagentRegistry? = null
) {
    /**
     * 解析工具注册表。
     * - toolRegistry 为 null → 返回 null
     * - definition.tools 为 null → 使用 toolRegistry 中全部项
     * - definition.tools 为 emptyList() → 不使用任何项
     * - definition.tools 为特定列表 → 只使用指定的项
     */
    public fun resolveToolRegistry(definition: AgentDefinition): ToolRegistry? {
        if (toolRegistry == null) return null
        val filtered = ToolRegistry()
        if (definition.tools == null) {
            filtered.register(toolRegistry.all())
        } else {
            definition.tools.forEach { name ->
                toolRegistry.getOrNull(name)?.let { filtered.register(it) }
                    ?: log.warn("Runtime Tool not found: $name")
            }
        }
        return filtered
    }

    /**
     * 解析延迟加载工具注册表。
     * - lazyToolRegistry 为 null → 返回 null
     * - definition.tools 为 null → 使用 lazyToolRegistry 中全部项
     * - definition.tools 为 emptyList() → 不使用任何项
     * - definition.tools 为特定列表 → 只使用指定的项
     */
    public fun resolveLazyToolRegistry(definition: AgentDefinition): LazyToolRegistry? {
        if (lazyToolRegistry == null) return null
        val filtered = LazyToolRegistry()
        if (definition.tools == null) {
            filtered.register(lazyToolRegistry.all())
        } else {
            definition.tools.forEach { name ->
                lazyToolRegistry.getOrNull(name)?.let { filtered.register(it) }
            }
        }
        return filtered
    }

    /**
     * 解析工具集注册表。
     * - toolsetRegistry 为 null → 返回 null
     * - definition.toolsets 为 null → 使用 toolsetRegistry 中全部项
     * - definition.toolsets 为 emptyList() → 不使用任何项
     * - definition.toolsets 为特定列表 → 只使用指定的项
     */
    public fun resolveToolsetRegistry(definition: AgentDefinition): ToolsetRegistry? {
        if (toolsetRegistry == null) return null
        val filtered = ToolsetRegistry()
        if (definition.toolsets == null) {
            filtered.register(toolsetRegistry.all())
        } else {
            definition.toolsets.forEach { name ->
                toolsetRegistry.getOrNull(name)?.let { filtered.register(it) }
                    ?: log.warn("Runtime Toolset not found: $name")
            }
        }
        return filtered
    }

    /**
     * 解析技能注册表。
     * - skillRegistry 为 null → 返回 null
     * - definition.skills 为 null → 使用 skillRegistry 中全部项
     * - definition.skills 为 emptyList() → 不使用任何项
     * - definition.skills 为特定列表 → 只使用指定的项
     */
    public fun resolveSkillRegistry(definition: AgentDefinition): SkillRegistry? {
        if (skillRegistry == null) return null
        val filtered = SkillRegistry()
        if (definition.skills == null) {
            filtered.register(skillRegistry.all())
        } else {
            definition.skills.forEach { name ->
                skillRegistry.getOrNull(name)?.let { filtered.register(it) }
                    ?: log.warn("Runtime Skill not found: $name")
            }
        }
        return filtered
    }

    /**
     * 解析子代理注册表。
     * - subagentRegistry 为 null → 返回 null
     * - definition.subagents 为 null → 使用 subagentRegistry 中全部项
     * - definition.subagents 为 emptyList() → 不使用任何项
     * - definition.subagents 为特定列表 → 只使用指定的项
     */
    public fun resolveSubagentRegistry(definition: AgentDefinition): SubagentRegistry? {
        if (subagentRegistry == null) return null
        val filtered = SubagentRegistry()
        if (definition.subagents == null) {
            filtered.register(subagentRegistry.all())
        } else {
            definition.subagents.forEach { name ->
                subagentRegistry.getOrNull(name)?.let { filtered.register(it) }
                    ?: log.warn("Runtime Subagent not found: $name")
            }
        }
        return filtered
    }

    /**
     * 解析 LLM Provider。
     */
    public fun resolveLlmProvider(definition: AgentDefinition): LlmProvider {
        return when (definition.provider.name) {
            "anthropic" -> AnthropicProvider(
                baseUrl = definition.provider.baseUrl,
                model = definition.provider.model,
                apiKey = definition.provider.apiKey
            )
            "openai" -> OpenAiProvider(
                baseUrl = definition.provider.baseUrl,
                model = definition.provider.model,
                apiKey = definition.provider.apiKey
            )
            else -> error("Unknown provider: ${definition.provider.name}")
        }
    }

    /**
     * 解析 MCP Registry。
     * - definition.mcps 为空 → 返回 null
     * - toolsetRegistry 为 null → 使用空 ToolsetRegistry
     */
    public fun resolveMcpRegistry(definition: AgentDefinition, toolsetRegistry: ToolsetRegistry?): McpRegistry? {
        if (definition.mcps.isNullOrEmpty()) return null
        val registry = McpRegistry(
            toolsetRegistry = toolsetRegistry ?: ToolsetRegistry(),
            clientInfo = ClientInfo("agent", definition.version.toString())
        )
        definition.mcps.forEach { config ->
            val mcp = object : Mcp() {
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
            registry.register(mcp)
        }
        return registry
    }
}
