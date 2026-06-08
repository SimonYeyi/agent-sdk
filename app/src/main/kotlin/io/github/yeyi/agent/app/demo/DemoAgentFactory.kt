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
 * `MODEL_BASE_URL` / `MODEL_API_KEY` 读取(经由 `BuildConfig` 注入)。Demo 用途。
 */
object DemoAgentFactory {

    fun create(): Agent {
        val client: LlmClient = when (BuildConfig.MODEL_PROVIDER.lowercase()) {
            "anthropic" -> AnthropicClient(
                apiKey = BuildConfig.MODEL_API_KEY,
                model = BuildConfig.MODEL_NAME,
                baseUrl = BuildConfig.MODEL_BASE_URL.ifEmpty { "https://api.anthropic.com" },
            )
            "openai" -> OpenAiClient(
                apiKey = BuildConfig.MODEL_API_KEY,
                model = BuildConfig.MODEL_NAME,
                baseUrl = BuildConfig.MODEL_BASE_URL.ifEmpty { "https://api.openai.com" },
            )
            else -> error(
                "Unsupported MODEL_PROVIDER: ${BuildConfig.MODEL_PROVIDER}. Use 'openai' or 'anthropic'."
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
}
