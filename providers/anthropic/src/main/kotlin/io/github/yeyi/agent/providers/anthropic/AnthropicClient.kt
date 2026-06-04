package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.core.llm.ChatRequest
import io.github.yeyi.agent.core.llm.ChatResponse
import io.github.yeyi.agent.core.llm.LlmClient
import io.github.yeyi.agent.core.llm.StreamEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

public class AnthropicClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-6",
    private val baseUrl: String = "https://api.anthropic.com",
    private val httpClient: HttpClient = defaultAnthropicHttpClient(),
) : LlmClient {
    override val providerName: String = "anthropic"

    override suspend fun chat(request: ChatRequest): ChatResponse {
        TODO("Anthropic chat() 由 Task 6.4 实现")
    }

    override fun chatStream(request: ChatRequest): Flow<StreamEvent> {
        TODO("Anthropic chatStream() 由 Task 6.6 实现")
    }
}

public fun defaultAnthropicHttpClient(): HttpClient = HttpClient(CIO) {
    expectSuccess = true
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(Logging) {
        level = LogLevel.NONE
    }
}
