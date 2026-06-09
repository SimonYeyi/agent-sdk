package io.github.yeyi.agent.app.demo

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.app.BuildConfig
import io.github.yeyi.agent.app.demo.tools.CalculatorTool
import io.github.yeyi.agent.app.demo.tools.GetCurrentTimeTool
import io.github.yeyi.agent.app.demo.tools.WebSearchMockTool
import io.github.yeyi.agent.llm.LlmClient
import io.github.yeyi.agent.providers.anthropic.AnthropicClient
import io.github.yeyi.agent.providers.openai.OpenAiClient

/**
 * 构造一个配好演示 Tool 与默认 LLM Client 的 Agent。
 *
 * **配置来源**:从 `local.properties` 的 `MODEL_API_KEY` / `MODEL_BASE_URL` /
 * `MODEL_NAME` 读取(经由 `BuildConfig` 注入,Demo 用途)。`MODEL_PROVIDER`
 * 是可选的。
 *
 * **Provider 解析优先级**:
 * 1. `MODEL_PROVIDER` 非空 → 必须为 `openai` 或 `anthropic`,否则报错
 * 2. `MODEL_PROVIDER` 为空 → 简单 `baseUrl.contains("anthropic", ignoreCase = true)`
 *    推断,命中则 Anthropic,否则 OpenAI(覆盖 OpenAI 兼容代理场景)
 *
 * **fail-fast**:`MODEL_API_KEY` / `MODEL_BASE_URL` / `MODEL_NAME` 任一为空
 * → 抛 [IllegalStateException]。工厂的职责是"忠实翻译完整配置",不静默
 * 回落。SDK 直接用户想"少配即用"走 `OpenAiClient.official(apiKey)` /
 * `AnthropicClient.official(apiKey)`。
 *
 * build.gradle.kts 只做 raw 透传 + Java 字面量转义,所有解析在这里完成。
 */
object DemoAgentFactory {

    private const val PROVIDER_OPENAI = "openai"
    private const val PROVIDER_ANTHROPIC = "anthropic"

    fun create(): Agent {
        val providerRaw = BuildConfig.MODEL_PROVIDER.unquote().trim().lowercase()
        val apiKey = BuildConfig.MODEL_API_KEY.unquote().requireNonEmpty("MODEL_API_KEY")
        val baseUrl = BuildConfig.MODEL_BASE_URL.unquote().requireNonEmpty("MODEL_BASE_URL")
        val model = BuildConfig.MODEL_NAME.unquote().requireNonEmpty("MODEL_NAME")

        val provider: String = when {
            providerRaw.isNotEmpty() -> {
                check(providerRaw == PROVIDER_OPENAI || providerRaw == PROVIDER_ANTHROPIC) {
                    "Unsupported MODEL_PROVIDER: '$providerRaw'. " +
                        "Use 'openai' or 'anthropic' (or leave empty to infer from MODEL_BASE_URL)."
                }
                providerRaw
            }
            baseUrl.contains(PROVIDER_ANTHROPIC, ignoreCase = true) -> PROVIDER_ANTHROPIC
            else -> PROVIDER_OPENAI
        }

        val client: LlmClient = if (provider == PROVIDER_ANTHROPIC) {
            AnthropicClient(apiKey = apiKey, model = model, baseUrl = baseUrl)
        } else {
            OpenAiClient(apiKey = apiKey, model = model, baseUrl = baseUrl)
        }
        return agent {
            systemPrompt = "你是一个 helpful 助手。优先使用工具完成任务。"
            llmClient = client
            tool(GetCurrentTimeTool())
            tool(CalculatorTool())
            tool(WebSearchMockTool())
            maxIterations = 8
        }
    }

    /**
     * 去除字符串首尾空白,再剥除可能包裹的双引号——兼容 `local.properties` 里
     * `MODEL_API_KEY="sk-..."` 这种带引号的写法。
     */
    private fun String.unquote(): String {
        val t = trim()
        return if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t.substring(1, t.length - 1) else t
    }

    private fun String.requireNonEmpty(fieldName: String): String {
        if (isEmpty()) {
            throw IllegalStateException("$fieldName is required (set it in local.properties)")
        }
        return this
    }
}
