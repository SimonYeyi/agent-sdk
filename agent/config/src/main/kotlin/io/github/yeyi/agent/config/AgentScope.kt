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
 * Agent 运行时上下文，封装 memory 和各组件注册表。
 *
 * @param memory 会话记忆（必需）
 * @param toolRegistry 工具注册表
 * @param lazyToolRegistry 延迟加载工具注册表
 * @param toolsetRegistry 工具集注册表
 * @param skillRegistry 技能注册表
 * @param subagentRegistry 子代理注册表
 */
public class AgentScope(
    public val memory: Memory,
    public val toolRegistry: ToolRegistry? = null,
    public val lazyToolRegistry: LazyToolRegistry? = null,
    public val toolsetRegistry: ToolsetRegistry? = null,
    public val skillRegistry: SkillRegistry? = null,
    public val subagentRegistry: SubagentRegistry? = null
) {
    /**
     * 创建 AgentResolver 实例。
     */
    public fun resolver(): AgentResolver = AgentResolver(this)
}

/**
 * 从 AgentScope 解析运行时所需的组件。
 */
public class AgentResolver internal constructor(private val scope: AgentScope) {

    /**
     * Agent 解析结果信息。
     */
    public class ResolveInfo(
        public val toolRegistry: ToolRegistry?,
        public val lazyToolRegistry: LazyToolRegistry?,
        public val toolsetRegistry: ToolsetRegistry?,
        public val skillRegistry: SkillRegistry?,
        public val subagentRegistry: SubagentRegistry?,
        public val llmProvider: LlmProvider,
        public val mcpRegistry: McpRegistry?
    )

    /**
     * 一次性解析所有组件，按正确顺序。
     */
    public fun resolve(definition: AgentDefinition): ResolveInfo {
        val (toolRegistry, lazyToolRegistry) = resolveTools(definition)
        val toolsetRegistry = resolveToolsets(definition)
        val skillRegistry = resolveSkills(definition)
        val subagentRegistry = resolveSubagents(definition)
        val llmProvider = resolveProvider(definition)
        val mcpRegistry = resolveMcps(definition, toolsetRegistry)
        return ResolveInfo(
            toolRegistry = toolRegistry,
            lazyToolRegistry = lazyToolRegistry,
            toolsetRegistry = toolsetRegistry,
            skillRegistry = skillRegistry,
            subagentRegistry = subagentRegistry,
            llmProvider = llmProvider,
            mcpRegistry = mcpRegistry
        )
    }

    private fun resolveTools(definition: AgentDefinition): Pair<ToolRegistry?, LazyToolRegistry?> {
        val resolvedToolRegistry = ToolRegistry()
        val resolvedLazyToolRegistry = LazyToolRegistry()

        if (definition.tools == null) {
            resolvedToolRegistry.register(scope.toolRegistry?.all() ?: emptyList())
            resolvedLazyToolRegistry.register(scope.lazyToolRegistry?.all() ?: emptyList())
        } else {
            definition.tools.forEach { name ->
                scope.lazyToolRegistry?.getOrNull(name)?.let {
                    resolvedLazyToolRegistry.register(it)
                } ?: scope.toolRegistry?.getOrNull(name)?.let {
                    resolvedToolRegistry.register(it)
                } ?: log.warn("Runtime Tool not found: $name")
            }
        }

        return resolvedToolRegistry to if (resolvedLazyToolRegistry.isEmpty()) null else resolvedLazyToolRegistry
    }

    private fun resolveToolsets(definition: AgentDefinition): ToolsetRegistry? {
        if (scope.toolsetRegistry == null) return null
        val resolved = ToolsetRegistry()
        if (definition.toolsets == null) {
            resolved.register(scope.toolsetRegistry.all())
        } else {
            definition.toolsets.forEach { name ->
                scope.toolsetRegistry.getOrNull(name)?.let { resolved.register(it) }
                    ?: log.warn("Runtime Toolset not found: $name")
            }
        }
        return if (resolved.isEmpty()) null else resolved
    }

    private fun resolveSkills(definition: AgentDefinition): SkillRegistry? {
        if (scope.skillRegistry == null) return null
        val resolved = SkillRegistry()
        if (definition.skills == null) {
            resolved.register(scope.skillRegistry.all())
        } else {
            definition.skills.forEach { name ->
                scope.skillRegistry.getOrNull(name)?.let { resolved.register(it) }
                    ?: log.warn("Runtime Skill not found: $name")
            }
        }
        return if (resolved.isEmpty()) null else resolved
    }

    private fun resolveSubagents(definition: AgentDefinition): SubagentRegistry? {
        if (scope.subagentRegistry == null) return null
        val resolved = SubagentRegistry()
        if (definition.subagents == null) {
            resolved.register(scope.subagentRegistry.all())
        } else {
            definition.subagents.forEach { name ->
                scope.subagentRegistry.getOrNull(name)?.let { resolved.register(it) }
                    ?: log.warn("Runtime Subagent not found: $name")
            }
        }
        return if (resolved.isEmpty()) null else resolved
    }

    private fun resolveProvider(definition: AgentDefinition): LlmProvider {
        return when (definition.provider) {
            is AgentDefinition.Provider.OpenAi -> OpenAiProvider(
                baseUrl = definition.provider.baseUrl,
                model = definition.provider.model,
                apiKey = definition.provider.apiKey
            )

            is AgentDefinition.Provider.Anthropic -> AnthropicProvider(
                baseUrl = definition.provider.baseUrl,
                model = definition.provider.model,
                apiKey = definition.provider.apiKey
            )
        }
    }

    private fun resolveMcps(
        definition: AgentDefinition,
        toolsetRegistry: ToolsetRegistry?
    ): McpRegistry? {
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
