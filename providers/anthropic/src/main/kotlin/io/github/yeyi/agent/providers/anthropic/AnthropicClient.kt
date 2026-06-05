package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.error.AgentException
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.LlmClient
import io.github.yeyi.agent.llm.StreamEvent
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

public class AnthropicClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-6",
    private val baseUrl: String = "https://api.anthropic.com",
    private val httpClient: HttpClient = defaultAnthropicHttpClient(),
) : LlmClient {
    override val providerName: String = "anthropic"

    public companion object {
        public fun defaultAnthropicHttpClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
            }
        }
    }

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val anthropicReq = mapToAnthropic(this.model, request)
        val resp: HttpResponse = try {
            httpClient.post("$baseUrl/v1/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(anthropicReq)
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            throw AgentException.LlmError(t)
        }
        if (!resp.status.isSuccess()) {
            throw AgentException.LlmError(
                RuntimeException("HTTP ${resp.status.value}: ${resp.bodyAsText()}")
            )
        }
        val parsed: AnthropicChatResponse = try {
            resp.body()
        } catch (t: Throwable) {
            throw AgentException.InvalidResponse("Anthropic body parse: ${t.message}")
        }
        return mapAnthropicToCore(parsed)
    }

    override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flow {
        val anthropicReq = mapToAnthropic(this@AnthropicClient.model, request).copy(stream = true)
        try {
            httpClient.preparePost("$baseUrl/v1/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(anthropicReq)
            }.execute { resp ->
                if (!resp.status.isSuccess()) {
                    throw AgentException.LlmError(
                        RuntimeException("HTTP ${resp.status.value}: ${resp.bodyAsText()}")
                    )
                }
                val channel = resp.bodyAsChannel()
                val lineFlow = flow {
                    while (true) {
                        val line = channel.readUTF8Line() ?: break
                        emit(line)
                    }
                    // Anthropic SSE decoder uses empty lines to delimit events.
                    // Emit a trailing empty line so the final event is flushed,
                    // matching the prior byte-by-byte parser's behavior.
                    emit("")
                }
                decodeAnthropicSse(lineFlow).collect { emit(it) }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (t is AgentException) throw t
            throw AgentException.LlmError(t)
        }
    }
}
