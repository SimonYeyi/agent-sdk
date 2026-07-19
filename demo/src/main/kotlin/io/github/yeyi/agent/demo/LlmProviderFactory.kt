package io.github.yeyi.agent.demo

import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.providers.anthropic.AnthropicProvider
import io.github.yeyi.agent.providers.openai.OpenAiProvider

/**
 * Demo LLM Provider 工厂。
 *
 * 从 BuildConfig 读取配置（由 local.properties 注入）:
 * - MODEL_PROVIDER: "openai" 或 "anthropic" (默认根据 URL 推断)
 * - MODEL_BASE_URL: API 地址
 * - MODEL_API_KEY: API 密钥
 * - MODEL_NAME: 模型名称
 */
object LlmProviderFactory {

    private const val PROVIDER_OPENAI = "openai"
    private const val PROVIDER_ANTHROPIC = "anthropic"

    fun create(): LlmProvider {
        val apiKey = BuildConfig.MODEL_API_KEY
        val baseUrl = BuildConfig.MODEL_BASE_URL
        val model = BuildConfig.MODEL_NAME
        val provider = BuildConfig.MODEL_PROVIDER

        require(apiKey.isNotEmpty()) { "MODEL_API_KEY is required" }
        require(baseUrl.isNotEmpty()) { "MODEL_BASE_URL is required" }

        val providerType = when {
            provider.isNotEmpty() -> provider
            baseUrl.contains("anthropic", ignoreCase = true) -> PROVIDER_ANTHROPIC
            else -> PROVIDER_OPENAI
        }

        return if (providerType == PROVIDER_ANTHROPIC) {
            AnthropicProvider(
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl
            )
        } else {
            OpenAiProvider(
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl
            )
        }
    }
}
