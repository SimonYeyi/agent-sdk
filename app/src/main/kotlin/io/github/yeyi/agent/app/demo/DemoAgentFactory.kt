package io.github.yeyi.agent.app.demo

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.app.BuildConfig
import io.github.yeyi.agent.app.demo.skills.WeatherSkill
import io.github.yeyi.agent.app.demo.tools.CalculatorTool
import io.github.yeyi.agent.app.demo.tools.GetCurrentTimeTool
import io.github.yeyi.agent.app.demo.tools.GetLocationTool
import io.github.yeyi.agent.app.demo.tools.GetWeatherTool
import io.github.yeyi.agent.app.demo.tools.WebSearchMockTool
import io.github.yeyi.agent.hook.LoggingHook
import io.github.yeyi.agent.hook.hook
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.providers.anthropic.AnthropicProvider
import io.github.yeyi.agent.providers.openai.OpenAiProvider
import io.github.yeyi.agent.skill.skills

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

    fun create(memory: Memory? = null): Agent {
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

        val llmProvider: LlmProvider = if (provider == PROVIDER_ANTHROPIC) {
            AnthropicProvider(apiKey = apiKey, model = model, baseUrl = baseUrl)
        } else {
            OpenAiProvider(apiKey = apiKey, model = model, baseUrl = baseUrl)
        }
        return agent {
            if (memory != null) memory(memory)
            systemPrompt("你是一个 helpful 助手。优先使用工具完成任务。")
            llmProvider(llmProvider)
            tool(GetCurrentTimeTool())
            tool(CalculatorTool())
            tool(WebSearchMockTool())
            skills(listOf(WeatherSkill()))
            tool(GetLocationTool())
            tool(GetWeatherTool())
            hook(LoggingHook())
        }
    }

    private fun String.requireNonEmpty(fieldName: String): String {
        check(isNotEmpty()) { "$fieldName is required (set it in local.properties)" }
        return this
    }
}
