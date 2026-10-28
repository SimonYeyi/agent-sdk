package io.github.yeyi.agent.demo.realtime.smartCockpit

import io.github.yeyi.agent.config.AgentDefinition
import io.github.yeyi.agent.config.AgentRegistry
import io.github.yeyi.agent.config.AgentScope
import io.github.yeyi.agent.demo.realtime.BuildConfig
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.team.BossAgent
import io.github.yeyi.agent.team.spawnBoss
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.tool.lazy_loading.LazyTool
import io.github.yeyi.agent.tool.lazy_loading.LazyToolRegistry
import io.github.yeyi.agent.toolset.ToolsetRegistry

/**
 * Smart Cockpit BossAgent — 配置驱动 spawn 模型。
 *
 * 配置与运行时分离：
 * - [definition] 声明完整 [AgentDefinition]（persona / provider / 能力名称列表），注册进 [AgentRegistry]
 * - [scope] 组装运行时上下文 [AgentScope]：注册表全集 + 会话记忆（记忆来自 session 持久化）
 * - [create] 通过 [spawnBoss] 构建 [BossAgent]，供全双工语音委托共享同一实例
 */
object CockpitAgent {

    /** 定义注册名，session 元数据中的 AgentReference 与之一致，保证恢复时重新 spawn。 */
    public const val NAME: String = "cockpit"

    private val registry = AgentRegistry().apply { register(definition()) }

    /**
     * 配置驱动定义：能力按名称声明，运行时由 [AgentScope.resolver] 从 scope 注册表投影。
     *
     * persona 在 boss 场景由框架收敛（角色固定为团队老板），必须留空；
     * 行为风格约束经 [AgentDefinition.instructions] 配置，由 [io.github.yeyi.agent.team.spawnBoss]
     * 以 extra 段落写入 system prompt。
     */
    private fun definition(): AgentDefinition = AgentDefinition(
        name = NAME,
        version = 1,
        instructions = "回复简洁",
        provider = providerFromBuildConfig(),
        maxIterations = 40,
        maxMemoryRounds = 40,
        tools = listOf("get_time", "get_date", "get_car_status", "get_energy"),
        toolsets = listOf("comfort_control", "cabin_environment", "driving_assist"),
        skills = listOf("driving_mode", "rest_mode", "movie_mode", "go_home"),
        subagents = listOf("media_expert", "navigation_expert"),
    )

    /** 运行时上下文：注册表全集 + 外部注入记忆（session.memory）。 */
    private fun scope(memory: Memory): AgentScope = AgentScope(
        memory = memory,
        toolRegistry = ToolRegistry().apply {
            register(GetTimeTool())
            register(GetDateTool())
        },
        lazyToolRegistry = LazyToolRegistry().apply {
            register(LazyTool(GetCarStatusTool()))
            register(LazyTool(GetEnergyTool(), LazyTool.Level.TOOL))
        },
        toolsetRegistry = ToolsetRegistry().apply {
            register(comfortControlToolset)
            register(cabinEnvironmentToolset)
            register(drivingAssistToolset)
        },
        skillRegistry = SkillRegistry().apply {
            register(DrivingModeSkill())
            register(RestModeSkill())
            register(MovieModeSkill())
            register(GoHomeSkill())
        },
        subagentRegistry = SubagentRegistry().apply {
            register(CockpitSubagents.MediaExpertSubagent())
            register(CockpitSubagents.NavigationExpertSubagent())
        },
    )

    /**
     * 构建共享 Boss。
     *
     * @param memory 会话记忆（持久化 Memory 时重启可续聊）
     */
    fun create(memory: Memory): BossAgent {
        val definition = requireNotNull(registry.getOrNull(NAME)) {
            "CockpitAgent definition not registered"
        }
        return definition.spawnBoss(scope(memory))
    }

    private fun providerFromBuildConfig(): AgentDefinition.Provider {
        val provider = BuildConfig.MODEL_PROVIDER
        val baseUrl = BuildConfig.MODEL_BASE_URL
        val apiKey = BuildConfig.MODEL_API_KEY
        val model = BuildConfig.MODEL_NAME

        require(baseUrl.isNotEmpty()) { "MODEL_BASE_URL is required (set it in local.properties)" }
        require(apiKey.isNotEmpty()) { "MODEL_API_KEY is required (set it in local.properties)" }
        require(model.isNotEmpty()) { "MODEL_NAME is required (set it in local.properties)" }

        val providerType = when {
            provider.isNotEmpty() -> provider
            baseUrl.contains("anthropic", ignoreCase = true) -> "anthropic"
            else -> "openai"
        }
        return when (providerType) {
            "anthropic" -> AgentDefinition.Provider.Anthropic(
                baseUrl = baseUrl,
                model = model,
                apiKey = apiKey
            )

            "openai" -> AgentDefinition.Provider.OpenAi(
                baseUrl = baseUrl,
                model = model,
                apiKey = apiKey
            )

            else -> error("Unsupported MODEL_PROVIDER: '$providerType'. Use 'openai' or 'anthropic'.")
        }
    }
}
