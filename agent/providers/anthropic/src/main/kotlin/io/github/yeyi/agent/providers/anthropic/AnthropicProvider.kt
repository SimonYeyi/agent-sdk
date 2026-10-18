package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readLineStrict
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
 * - [thinking] 是否开启思考模式，默认 true。Anthropic Messages API 协议统一，
 *   思考字段只有 `thinking` 对象一种格式。开启时发送 `{"type":"enabled",
 *   "budget_tokens":4096}`；显式传 false 发送 `{"type":"disabled"}`，覆盖
 *   Sonnet 5 等新模型默认开启的自适应思考，避免简单任务消耗思考 token。
 * - [configure] 可选的 HttpClient 配置函数，会与内部默认配置合并。
 *   内部核心配置（JSON 序列化、超时）会被强制覆盖，其余插件会被保留。
 *
 * 快捷构造：[official] 使用官方 endpoint 和默认配置。
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
    private val thinking: Boolean = true,
    private val configure: (HttpClientConfig<*>.() -> Unit)? = null,
) : LlmProvider {
    override val name: String = "anthropic"

    private val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            configure?.invoke(this)
            expectSuccess = true
            installOrReplace(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                })
            }
            installOrReplace(HttpTimeout) {
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
            }
        }
    }

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val anthropicReq = mapToAnthropic(model, request, thinking)
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

    override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow {
        val anthropicReq = mapToAnthropic(model, request, thinking).copy(stream = true)
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
                        val line = channel.readLineStrict() ?: break
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

    public companion object {
        private const val DEFAULT_MODEL: String = "claude-sonnet-4-6"
        private const val DEFAULT_BASE_URL: String = "https://api.anthropic.com"

        public fun official(
            apiKey: String,
            configure: (HttpClientConfig<*>.() -> Unit)? = null,
        ): AnthropicProvider = AnthropicProvider(
            apiKey = apiKey,
            model = DEFAULT_MODEL,
            baseUrl = DEFAULT_BASE_URL,
            configure = configure,
        )
    }
}
