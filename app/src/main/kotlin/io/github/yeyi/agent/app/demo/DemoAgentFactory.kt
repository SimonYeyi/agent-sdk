package io.github.yeyi.agent.app.demo

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.app.BuildConfig
import io.github.yeyi.agent.app.demo.mcp.CalculatorMcp
import io.github.yeyi.agent.app.demo.mcp.LiveScoreMcp
import io.github.yeyi.agent.app.demo.skills.NewsSkill
import io.github.yeyi.agent.app.demo.skills.WeatherSkill
import io.github.yeyi.agent.app.demo.subagents.WeatherExpertSubagent
import io.github.yeyi.agent.app.demo.tools.GetLocationTool
import io.github.yeyi.agent.app.demo.tools.GetWeatherTool
import io.github.yeyi.agent.app.demo.tools.WebSearchTool
import io.github.yeyi.agent.app.log.HttpLogger
import io.github.yeyi.agent.hook.HookPipeline
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.mcp.ClientInfo
import io.github.yeyi.agent.mcp.McpRegistry
import io.github.yeyi.agent.mcp.mcp
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.providers.anthropic.AnthropicProvider
import io.github.yeyi.agent.providers.openai.OpenAiProvider
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.skill.skills
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.subagent.subagents
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * 构造一个配好演示 Tool 与默认 LLM Provider 的 Agent。
 *
 * **配置来源**:从 `local.properties` 的 `MODEL_PROVIDER` / `MODEL_BASE_URL` /
 * `MODEL_API_KEY` / `MODEL_NAME` 读取(经由 `BuildConfig` 注入)。值由用户
 * 保证是干净的(无引号、无前后空格),不在工厂里做清理。`MODEL_PROVIDER` 是
 * 可选的。
 *
 * **Provider 解析优先级**:
 * 1. `MODEL_PROVIDER` 非空 → 必须严格等于 `openai` 或 `anthropic`
 * 2. `MODEL_PROVIDER` 为空 → `baseUrl.contains("anthropic", ignoreCase = true)`
 *    命中则 Anthropic,否则 OpenAI
 *
 * **核心配置校验**:`MODEL_API_KEY` / `MODEL_BASE_URL` / `MODEL_NAME` 必填,
 * 任一为空 → 抛 [IllegalStateException];`MODEL_PROVIDER` 显式给了非法值
 * 也抛。`baseUrl` 是不是合法 URL、API key 是不是有效,留给 LLM provider 运行时
 * 暴露,工厂不做猜测性校验。
 */
object DemoAgentFactory {

    private const val PROVIDER_OPENAI = "openai"
    private const val PROVIDER_ANTHROPIC = "anthropic"

    fun create(memory: Memory? = null, hook: AgentHook? = null): Agent {
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

        val httpClient = httpClient()

        val llmProvider: LlmProvider = if (provider == PROVIDER_ANTHROPIC) {
            AnthropicProvider(apiKey = apiKey, model = model, baseUrl = baseUrl, httpClient)
        } else {
            OpenAiProvider(apiKey = apiKey, model = model, baseUrl = baseUrl, httpClient)
        }

        val mcpRegistry = McpRegistry(ClientInfo("agent-sdk-app", "0.1.0")).apply {
            // Local MCP server
            register(CalculatorMcp())
            // Online MCP servers
            register(LiveScoreMcp(httpClient))
        }

        return agent {
            persona(Persona(role = "你是一个 helpful 助手，优先使用工具完成任务。"))
            memory(memory ?: InMemoryMemory(), 1)
            llmProvider(llmProvider)
            tool(WebSearchTool())
            tool(GetLocationTool())
            val skillRegistry = SkillRegistry()
            skillRegistry.register(NewsSkill())
            skillRegistry.register(WeatherSkill())
            skillRegistry.registerTools(listOf(GetWeatherTool()))
            skills(skillRegistry)
            mcp(mcpRegistry)
            hook(hook ?: HookPipeline(logging = true))
            val subagentRegistry = SubagentRegistry()
            subagentRegistry.register(WeatherExpertSubagent())
            subagents(registry = subagentRegistry, dynamic = true)
        }
    }

    private fun String.requireNonEmpty(fieldName: String): String {
        check(isNotEmpty()) { "$fieldName is required (set it in local.properties)" }
        return this
    }

    private fun httpClient() = HttpClient(CIO) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
        install(Logging) {
            logger = HttpLogger()
            level = LogLevel.ALL
        }
    }
}
