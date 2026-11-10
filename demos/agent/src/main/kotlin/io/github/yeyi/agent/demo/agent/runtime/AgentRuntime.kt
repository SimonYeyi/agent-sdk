package io.github.yeyi.agent.demo.agent.runtime

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.approval.ApprovalHook
import io.github.yeyi.agent.demo.agent.BuildConfig
import io.github.yeyi.agent.demo.agent.runtime.approval.AgentApprover
import io.github.yeyi.agent.demo.agent.runtime.mcp.CalculatorMcp
import io.github.yeyi.agent.demo.agent.runtime.mcp.IotHaMcp
import io.github.yeyi.agent.demo.agent.runtime.skills.NewsSkill
import io.github.yeyi.agent.demo.agent.runtime.skills.WeatherSkill
import io.github.yeyi.agent.demo.agent.runtime.subagents.ExternalHelpSubagent
import io.github.yeyi.agent.demo.agent.runtime.subagents.WeatherExpertSubagent
import io.github.yeyi.agent.demo.agent.runtime.tools.ChooseCityTool
import io.github.yeyi.agent.demo.agent.runtime.tools.GetDateTimeTool
import io.github.yeyi.agent.demo.agent.runtime.tools.GetLocationTool
import io.github.yeyi.agent.demo.agent.runtime.tools.WebSearchTool
import io.github.yeyi.agent.demo.agent.log.HttpLogger
import io.github.yeyi.agent.demo.agent.runtime.tools.WeatherCardTool
import io.github.yeyi.agent.demo.agent.ui.registerDemoCardTemplates
import io.github.yeyi.agent.genui.adapter.UiRenderAdapter
import io.github.yeyi.agent.genui.adapter.default.DefaultRenderAdapter
import io.github.yeyi.agent.genui.adapter.default.registerDefaultComponents
import io.github.yeyi.agent.genui.tool.GenUiTool
import io.github.yeyi.agent.hook.HookPipeline
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.mcp.ClientInfo
import io.github.yeyi.agent.mcp.McpRegistry
import io.github.yeyi.agent.mcp.mcps
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.providers.anthropic.AnthropicProvider
import io.github.yeyi.agent.providers.openai.OpenAiProvider
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.skill.skills
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.subagent.subagents
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.tool.lazy_loading.LazyTool
import io.github.yeyi.agent.tool.lazy_loading.LazyToolRegistry
import io.github.yeyi.agent.tool.lazy_loading.lazyTools
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging

/**
 * 全局运行时：持有各 registry，按需组装出一个配好 Tool 与
 * 默认 LLM Provider 的 [Agent]。
 *
 * 与会话无关的 registry 是单例字段：所有 agent 复用同一份，而不是每次重建。
 *
 * 前台组件（产卡工具、审批器）是 agent 私有的：随 [buildAgent] 现场构造。
 */
object AgentRuntime {

    init {
        io.github.yeyi.agent.log.Logging.setDelegate { level, tag, msg ->
            android.util.Log.println(level.toAndroidLevel(), tag, msg)
        }
    }

    /**
     * 默认 LLM Provider：从 `local.properties` 读配置后建一次，所有 agent 共用。
     */
    private val provider: LlmProvider = createProvider()

    /**
     * hook 流水线：全局共享一份——日志 hook 与审批策略都不需要 agent 私有数据。
     *
     * 将来接审批 UI 时，per-agent 的不是策略而是**出口**（审批请求投到哪个会话），
     * 那时这条流水线要改回随 agent 构造。
     */
    val agentHook: AgentHook = HookPipeline(listOf(ApprovalHook(AgentApprover())), logging = true)

    /**
     * 全局工具注册表：不依赖 agent 身份、可跨 agent 共享的工具，全 agent 共用一份。
     *
     * 当前为空——demo 现有工具都要产卡，故都随 agent 构造。
     * 这里保留位置，是为了让「与 agent 无关的工具」有个明确的落点，而不是混进按 agent 构造的那批。
     */
    private val toolRegistry = ToolRegistry().apply {
        register(GetLocationTool())
        register(GetDateTimeTool())
        register(ChooseCityTool())
        // 模型现场排版的通用卡片：内容不定型时用它，不必为每种内容各写一个工具
        register(GenUiTool())
    }

    /**
     * 全局懒加载工具注册表：同样只放不依赖 agent 身份、可跨 agent 共享的 [LazyTool]，当前为空。
     */
    private val lazyToolRegistry = LazyToolRegistry().apply {
        register(LazyTool(WeatherCardTool()))
        register(LazyTool(WebSearchTool(), LazyTool.Level.TOOL))
    }

    private val skillRegistry = SkillRegistry().apply {
        register(NewsSkill())
        register(WeatherSkill())
    }

    private val subagentRegistry = SubagentRegistry().apply {
        register(WeatherExpertSubagent())
        register(ExternalHelpSubagent())
    }

    private val mcpRegistry = McpRegistry(
        ClientInfo(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME)
    ).apply {
        // Local MCP server
        register(CalculatorMcp())
        register(IotHaMcp(httpClientConfigure()))
    }

    /**
     * 卡片渲染后端：demo 全局唯一装配点，iOS 风格组件 + 演示工具组件注册一次，
     * 所有 agent/会话共用。UI 层经 [UiRenderAdapter.RenderCard] 消费卡片流，对后端差异无感。
     */
    val cardAdapter: UiRenderAdapter = DefaultRenderAdapter().apply {
        registerDefaultComponents()
        registerDemoCardTemplates()
    }

    /**
     * 构建一个配好演示 Tool 与默认 LLM Provider 的 Agent。
     *
     * @param agentId agent 身份标识
     * @param memory 会话记忆
     */
    fun buildAgent(agentId: String, memory: Memory): Agent {
        return agent {
            id(agentId)
            persona(AgentPersona(role = "你是一个 helpful 助手，优先使用工具完成任务，结果最好以卡片的形式展示"))
            memory(memory)
            llmProvider(provider)
            // 产卡工具:卡片内嵌工具结果 metadata,随事件流与记忆链路送达消费方
            // 全局工具与产卡工具合并进一份新注册表传入:全局注册表只读,不被写入
            tools(toolRegistry.all())
            lazyTools(lazyToolRegistry)
            skills(skillRegistry)
            mcps(mcpRegistry)
            hook(agentHook)
            subagents(registry = subagentRegistry, dynamic = true)
        }
    }
}

/**
 * 按 `local.properties` 的配置建默认 LLM Provider。
 *
 * **Provider 解析优先级**:
 * 1. `MODEL_PROVIDER` 非空 → 必须严格等于 `openai` 或 `anthropic`
 * 2. `MODEL_PROVIDER` 为空 → `baseUrl.contains("anthropic", ignoreCase = true)`
 *    命中则 Anthropic,否则 OpenAI
 *
 * **核心配置校验**:`MODEL_API_KEY` / `MODEL_BASE_URL` / `MODEL_NAME` 必填,
 * 任一为空 → 抛 [IllegalStateException];`MODEL_PROVIDER` 显式给了非法值
 * 也抛。`baseUrl` 是不是合法 URL、API key 是不是有效,留给 LLM provider 运行时
 * 暴露,构建过程不做猜测性校验。
 */
private fun createProvider(): LlmProvider {
    val providerRaw = BuildConfig.MODEL_PROVIDER
    val apiKey = BuildConfig.MODEL_API_KEY.requireNonEmpty("MODEL_API_KEY")
    val baseUrl = BuildConfig.MODEL_BASE_URL.requireNonEmpty("MODEL_BASE_URL")
    val model = BuildConfig.MODEL_NAME.requireNonEmpty("MODEL_NAME")

    val provider: String = when {
        providerRaw.isNotEmpty() -> {
            check(providerRaw in setOf(PROVIDER_OPENAI, PROVIDER_ANTHROPIC)) {
                "Unsupported MODEL_PROVIDER: '$providerRaw'. " +
                        "Use 'openai' or 'anthropic' (or leave empty to infer from MODEL_BASE_URL)."
            }
            providerRaw
        }

        baseUrl.contains(PROVIDER_ANTHROPIC, ignoreCase = true) -> PROVIDER_ANTHROPIC
        else -> PROVIDER_OPENAI
    }

    return if (provider == PROVIDER_ANTHROPIC) {
        AnthropicProvider(
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            httpConfigure = httpClientConfigure()
        )
    } else {
        OpenAiProvider(
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            httpConfigure = httpClientConfigure()
        )
    }
}

private const val PROVIDER_OPENAI = "openai"
private const val PROVIDER_ANTHROPIC = "anthropic"

private fun String.requireNonEmpty(fieldName: String): String {
    check(isNotEmpty()) { "$fieldName is required (set it in local.properties)" }
    return this
}

private fun httpClientConfigure(): (HttpClientConfig<*>.() -> Unit) = {
    install(Logging) {
        logger = HttpLogger()
        level = LogLevel.ALL
    }
}

private fun io.github.yeyi.agent.log.LogLevel.toAndroidLevel(): Int = when (this) {
    io.github.yeyi.agent.log.LogLevel.DEBUG -> android.util.Log.DEBUG
    io.github.yeyi.agent.log.LogLevel.INFO -> android.util.Log.INFO
    io.github.yeyi.agent.log.LogLevel.WARN -> android.util.Log.WARN
    io.github.yeyi.agent.log.LogLevel.ERROR -> android.util.Log.ERROR
}
