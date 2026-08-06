package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.LlmProvider
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

/**
 * Anthropic API LLM Provider。
 *
 * 构造参数说明：
 * - [apiKey] Anthropic API Key（必填）
 * - [model] 模型名称，默认 [DEFAULT_MODEL]
 * - [baseUrl] API 地址，默认 [DEFAULT_BASE_URL]
 * - [httpClient] 可自定义 Ktor HTTP Client，不传则使用 [defaultAnthropicHttpClient]
 *
 * 快捷构造：[official] 使用官方 endpoint 和默认 HTTP Client。
 *
 * 示例：
 * ```
 * val provider = AnthropicProvider.official(apiKey = "sk-ant-...")
 * ```
 */
public class AnthropicProvider(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    private val httpClient: HttpClient = defaultAnthropicHttpClient(),
) : LlmProvider {
    override val name: String = "anthropic"

    public companion object {
        public const val DEFAULT_MODEL: String = "claude-sonnet-4-6"
        public const val DEFAULT_BASE_URL: String = "https://api.anthropic.com"

        public fun defaultAnthropicHttpClient(): HttpClient = HttpClient(CIO) {
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
        }

        public fun official(
            apiKey: String,
            httpClient: HttpClient = defaultAnthropicHttpClient(),
        ): AnthropicProvider = AnthropicProvider(
            apiKey = apiKey,
            model = DEFAULT_MODEL,
            baseUrl = DEFAULT_BASE_URL,
            httpClient = httpClient,
        )
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
        val anthropicReq = mapToAnthropic(this@AnthropicProvider.model, request).copy(stream = true)
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
