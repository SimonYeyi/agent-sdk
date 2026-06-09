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
 * **配置来源**: 从 `local.properties` 的 `MODEL_PROVIDER` / `MODEL_NAME` /
 * `MODEL_BASE_URL` / `MODEL_API_KEY` 读取(经由 `BuildConfig` 注入,Demo 用途)。
 *
 * **容错**: trim、剥离 local.properties 写法里可能包的双引号、空值回落到默认值。
 * build.gradle.kts 只做 raw 透传,所有解析在这里完成。
 */
object DemoAgentFactory {

    fun create(): Agent {
        val provider = BuildConfig.MODEL_PROVIDER.unquote().lowercase()
        val model = BuildConfig.MODEL_NAME.unquote().ifEmpty { "gpt-4o-mini" }
        val rawBaseUrl = BuildConfig.MODEL_BASE_URL.unquote()
        val apiKey = BuildConfig.MODEL_API_KEY.unquote()

        val client: LlmClient = when (provider) {
            "anthropic" -> AnthropicClient(
                apiKey = apiKey,
                model = model,
                baseUrl = rawBaseUrl.ifEmpty { "https://api.anthropic.com" },
            )
            "openai" -> OpenAiClient(
                apiKey = apiKey,
                model = model,
                baseUrl = rawBaseUrl.ifEmpty { "https://api.openai.com" },
            )
            else -> error(
                "Unsupported MODEL_PROVIDER: '${BuildConfig.MODEL_PROVIDER}'. Use 'openai' or 'anthropic'."
            )
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
}
