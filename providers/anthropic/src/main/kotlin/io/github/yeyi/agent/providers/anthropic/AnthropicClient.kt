package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.core.llm.ChatRequest
import io.github.yeyi.agent.core.llm.ChatResponse
import io.github.yeyi.agent.core.llm.LlmClient
import io.github.yeyi.agent.core.llm.StreamEvent
import io.github.yeyi.agent.providers.anthropic.dto.AnthropicChatResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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
        val anthropicReq = mapToAnthropic(this.model, request)
        val response: AnthropicChatResponse = httpClient.post("$baseUrl/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(anthropicReq)
        }.body()
        return mapAnthropicToCore(response)
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
