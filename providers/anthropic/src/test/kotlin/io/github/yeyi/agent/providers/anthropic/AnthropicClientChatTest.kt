package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnthropicClientChatTest {

    @Test
    fun `chat sends x-api-key and anthropic-version headers`() = runTest {
        var capturedHeaders: io.ktor.http.Headers? = null
        val engine = MockEngine { request ->
            capturedHeaders = request.headers
            respond(
                content = ByteReadChannel(
                    """{"id":"m1","model":"claude-sonnet-4-6","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":2}}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = AnthropicClient(apiKey = "sk-ant-xxx", model = "claude-sonnet-4-6", httpClient = http)
        client.chat(ChatRequest(messages = listOf(ChatMessage.User("hi"))))
        assertEquals("sk-ant-xxx", capturedHeaders?.get("x-api-key"))
        assertEquals("2023-06-01", capturedHeaders?.get("anthropic-version"))
    }

    @Test
    fun `chat posts to v1_messages endpoint`() = runTest {
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = ByteReadChannel(
                    """{"id":"m1","model":"claude-sonnet-4-6","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":2}}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = AnthropicClient(apiKey = "k", model = "claude-sonnet-4-6", httpClient = http)
        client.chat(ChatRequest(messages = listOf(ChatMessage.User("hi"))))
        assertTrue(capturedUrl!!.endsWith("/v1/messages"), "expected /v1/messages, got $capturedUrl")
    }

    @Test
    fun `chat returns ChatResponse with assistant text`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(
                    """{"id":"m1","model":"claude-sonnet-4-6","content":[{"type":"text","text":"hello back"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":2}}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = AnthropicClient(apiKey = "k", httpClient = http)
        val response = client.chat(ChatRequest(messages = listOf(ChatMessage.User("hi"))))
        assertEquals("hello back", response.message.content)
        assertEquals(0, response.message.toolCalls.size)
    }
}
