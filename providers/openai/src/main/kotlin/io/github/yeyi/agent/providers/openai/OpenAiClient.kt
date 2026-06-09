package io.github.yeyi.agent.providers.openai

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

public class OpenAiClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    private val httpClient: HttpClient = defaultHttpClient()
) : LlmClient {

    override val providerName: String = "openai"

    public companion object {
        public const val DEFAULT_MODEL: String = "gpt-4o-mini"
        public const val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"

        public fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
            }
        }

        public fun official(
            apiKey: String,
            httpClient: HttpClient = defaultHttpClient(),
        ): OpenAiClient = OpenAiClient(
            apiKey = apiKey,
            model = DEFAULT_MODEL,
            baseUrl = DEFAULT_BASE_URL,
            httpClient = httpClient,
        )
    }

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val openAiReq = mapToOpenAi(model, request, stream = false)
        val resp: HttpResponse = try {
            httpClient.post("$baseUrl/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(openAiReq)
            }
        } catch (t: Throwable) {
            throw AgentException.LlmError(t)
        }
        if (!resp.status.isSuccess()) {
            throw AgentException.LlmError(
                RuntimeException("HTTP ${resp.status.value}: ${resp.bodyAsText()}")
            )
        }
        val parsed: OpenAiChatResponse = try {
            resp.body()
        } catch (t: Throwable) {
            throw AgentException.InvalidResponse("OpenAI body parse: ${t.message}")
        }
        return mapFromOpenAi(parsed)
    }

    override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flow {
        val openAiReq = mapToOpenAi(model, request, stream = true)
        try {
            httpClient.preparePost("$baseUrl/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(openAiReq)
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
                }
                decodeOpenAiSseLines(lineFlow).collect { emit(it) }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (t is AgentException) throw t
            throw AgentException.LlmError(t)
        }
    }
}
