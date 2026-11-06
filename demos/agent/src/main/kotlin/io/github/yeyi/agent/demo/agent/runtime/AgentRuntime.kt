package io.github.yeyi.agent.demo.agent.runtime

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.demo.agent.BuildConfig
import io.github.yeyi.agent.demo.agent.runtime.mcp.CalculatorMcp
import io.github.yeyi.agent.demo.agent.runtime.mcp.IotHaMcp
import io.github.yeyi.agent.demo.agent.runtime.skills.NewsSkill
import io.github.yeyi.agent.demo.agent.runtime.skills.WeatherSkill
import io.github.yeyi.agent.demo.agent.runtime.subagents.ExternalHelpSubagent
import io.github.yeyi.agent.demo.agent.runtime.subagents.WeatherExpertSubagent
import io.github.yeyi.agent.demo.agent.runtime.tools.GetDateTimeTool
import io.github.yeyi.agent.demo.agent.runtime.tools.GetLocationTool
import io.github.yeyi.agent.demo.agent.runtime.tools.WebSearchTool
import io.github.yeyi.agent.demo.agent.log.HttpLogger
import io.github.yeyi.agent.demo.agent.runtime.tools.WeatherCardTool
import io.github.yeyi.agent.demo.agent.ui.registerDemoCardTemplates
import io.github.yeyi.agent.genui.adapter.UiRenderAdapter
import io.github.yeyi.agent.genui.adapter.default.DefaultRenderAdapter
import io.github.yeyi.agent.genui.adapter.default.registerDefaultComponents
import io.github.yeyi.agent.genui.tool.UiSinkRegistry
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
 * 全局运行时：持有各 registry 与卡片路由表，按需组装出一个配好 Tool 与
 * 默认 LLM Provider 的 [Agent]。
 *
 * registry 是单例字段：所有 agent 复用同一份 registry，而不是每次重建。
 *
 * 卡片旁路：路由表在这里，[sinkRegistry] 公开——使用方自持 sink，
 * 建 agent 时把 agentId 与自己的 sink 注册进来，agent 作废时解注册；
 * sink 的收集也就留在使用方那里。
 */
object AgentRuntime {

    init {
        io.github.yeyi.agent.log.Logging.setDelegate { level, tag, msg ->
            android.util.Log.println(level.toAndroidLevel(), tag, msg)
        }
    }

    /**
     * 挂到 agent 上的 hook。
     *
     * 改动只对之后构建的 agent 生效——已构建的 agent 保留各自那份 hook。
     */
    var agentHook: AgentHook = HookPipeline(logging = true)

    /** 卡片路由表：agentId → sink。产卡工具据此找到本 agent 的 sink。 */
    val sinkRegistry = UiSinkRegistry()

    /**
     * 卡片渲染后端：demo 全局唯一装配点，iOS 风格组件 + 演示工具组件注册一次，
     * 所有 agent/会话共用。UI 层经 [UiRenderAdapter.RenderCard] 消费卡片流，对后端差异无感。
     */
    val cardAdapter: UiRenderAdapter = DefaultRenderAdapter().apply {
        registerDefaultComponents()
        registerDemoCardTemplates()
    }

    /** 产卡工具在装配时装好，不随 buildAgent 现场组装。 */
    private val toolRegistry = ToolRegistry().apply {
        register(GetLocationTool(sinkRegistry))
        register(GetDateTimeTool(sinkRegistry))
    }

    private val lazyToolRegistry = LazyToolRegistry().apply {
        register(LazyTool(WeatherCardTool(sinkRegistry)))
        register(LazyTool(WebSearchTool(sinkRegistry), LazyTool.Level.TOOL))
    }

    private val skillRegistry = SkillRegistry().apply {
        register(NewsSkill())
        register(WeatherSkill())
    }

    private val subagentRegistry = SubagentRegistry().apply {
        register(WeatherExpertSubagent())
        register(ExternalHelpSubagent())
    }

    /**
     * 默认 LLM Provider：从 `local.properties` 读配置后建一次，所有 agent 共用。
     */
    private val provider: LlmProvider = createProvider()

    private val mcpRegistry = McpRegistry(
        ClientInfo(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME)
    ).apply {
        // Local MCP server
        register(CalculatorMcp())
        register(IotHaMcp(httpClientConfigure()))
    }

    /**
     * 构建一个配好演示 Tool 与默认 LLM Provider 的 Agent。
     *
     * @param agentId agent 身份标识：卡片旁路的路由键，产卡工具按
     *   [io.github.yeyi.agent.AgentContext.agentId] 反查自己的 sink。
     *   需先经 [sinkRegistry] 注册 agentId 与 sink，否则产卡工具找不到 sink。
     */
    fun buildAgent(agentId: String, memory: Memory): Agent {
        return agent {
            id(agentId)
            persona(AgentPersona(role = "你是一个 helpful 助手，优先使用工具完成任务。"))
            memory(memory)
            llmProvider(provider)
            // 产卡工具:产卡经 UiSink 旁路广播,记忆里只留回执短文本
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
