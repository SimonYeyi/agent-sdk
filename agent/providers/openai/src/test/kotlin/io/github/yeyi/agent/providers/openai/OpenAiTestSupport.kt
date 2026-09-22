package io.github.yeyi.agent.providers.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

internal val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")

internal fun mockOpenAiHttpClient(handler: MockRequestHandler): HttpClient =
    HttpClient(MockEngine(handler)) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

/**
 * 读取 MockEngine 捕获的请求体文本。
 * ContentNegotiation + kotlinx-json 序列化后的请求体固定是 [TextContent]。
 */
internal fun requestBodyText(body: OutgoingContent): String = when (body) {
    is TextContent -> body.text
    else -> error("Unexpected request body type: ${body::class.simpleName}")
}
