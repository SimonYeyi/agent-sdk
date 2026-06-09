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
import java.net.URI
import java.net.URISyntaxException

/**
 * 构造一个配好演示 Tool 与默认 LLM Client 的 Agent。
 *
 * **配置来源**:从 `local.properties` 的 `MODEL_API_KEY` / `MODEL_BASE_URL` /
 * `MODEL_NAME` 读取(经由 `BuildConfig` 注入,Demo 用途)。
 *
 * **Provider 推断**:不再单独配置 provider,而是从 `MODEL_BASE_URL` 解析
 * —— host 或 path 含 `anthropic` 时选 [AnthropicClient],否则 [OpenAiClient]
 * (覆盖 OpenAI 兼容代理场景)。
 *
 * **容错策略 (fail-fast)**:三件套任一缺失 / `MODEL_BASE_URL` 不是合法 URI
 * → 直接抛 [IllegalStateException]。Demo 工厂的职责是"忠实翻译完整配置",
 * 不为"用户忘了填"提供静默回落到 client 默认值的兜底。client 自带的
 * `OpenAiClient.official(apiKey)` / `AnthropicClient.official(apiKey)` 静态
 * 工厂才是"少配即用"的入口——SDK 直接用户走那边。
 *
 * build.gradle.kts 只做 raw 透传 + Java 字面量转义,所有解析在这里完成。
 */
object DemoAgentFactory {

    fun create(): Agent {
        val apiKey = BuildConfig.MODEL_API_KEY.unquote().requireNonEmpty("MODEL_API_KEY")
        val baseUrl = BuildConfig.MODEL_BASE_URL.unquote().requireNonEmpty("MODEL_BASE_URL")
        val model = BuildConfig.MODEL_NAME.unquote().requireNonEmpty("MODEL_NAME")

        val uri = try {
            URI(baseUrl)
        } catch (e: URISyntaxException) {
            throw IllegalStateException("MODEL_BASE_URL is not a valid URI: '$baseUrl'", e)
        }
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
            throw IllegalStateException(
                "MODEL_BASE_URL must include scheme and host: '$baseUrl'"
            )
        }

        val isAnthropic = uri.host.orEmpty().contains("anthropic", ignoreCase = true) ||
            uri.path.orEmpty().contains("anthropic", ignoreCase = true)

        val client: LlmClient = if (isAnthropic) {
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
