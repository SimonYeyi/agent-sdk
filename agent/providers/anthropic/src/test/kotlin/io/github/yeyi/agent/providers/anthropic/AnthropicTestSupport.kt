package io.github.yeyi.agent.providers.anthropic

import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

internal val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")

/**
 * 读取 MockEngine 捕获的请求体文本。
 * ContentNegotiation + kotlinx-json 序列化后的请求体固定是 [TextContent]。
 */
internal fun requestBodyText(body: OutgoingContent): String = when (body) {
    is TextContent -> body.text
    else -> error("Unexpected request body type: ${body::class.simpleName}")
}
