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
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readRemaining
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

    override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flow {
        val anthropicReq = mapToAnthropic(this@AnthropicClient.model, request).copy(stream = true)
        val lineFlow: Flow<String> = httpClient.post("$baseUrl/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(anthropicReq)
        }.bodyAsChannel().let { channel ->
            flow {
                // SSE 格式:每行以 \n 分隔,空行表示一个事件结束
                // Ktor 3.x 没有内置 SSE parser,这里用 byte-by-byte 累加
                val buffer = StringBuilder()
                while (!channel.isClosedForRead) {
                    val chunk = channel.readRemaining(4096)
                    while (!chunk.exhausted()) {
                        val byte = chunk.readByte()
                        val ch = byte.toInt().toChar()
                        if (ch == '\n') {
                            emit(buffer.toString())
                            buffer.clear()
                        } else if (ch != '\r') {
                            buffer.append(ch)
                        }
                    }
                }
                // 流结束时,若缓冲区还有未换行的尾行,先 emit 出去,再 emit 一个空行触发解码器收尾
                if (buffer.isNotEmpty()) {
                    emit(buffer.toString())
                    buffer.clear()
                }
                emit("")
            }
        }
        decodeAnthropicSse(lineFlow).collect { emit(it) }
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
