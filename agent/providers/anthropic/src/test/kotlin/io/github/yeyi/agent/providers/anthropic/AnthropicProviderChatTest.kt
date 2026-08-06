package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnthropicProviderChatTest {

    @Test
    fun `chat sends x-api-key and anthropic-version headers`() = runTest {
        var capturedHeaders: io.ktor.http.Headers? = null
        val http = mockAnthropicHttpClient { request ->
            capturedHeaders = request.headers
            respond(
                content = ByteReadChannel(
                    """{"id":"m1","model":"claude-sonnet-4-6","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":2}}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = AnthropicProvider(
            apiKey = "sk-ant-xxx",
            model = "claude-sonnet-4-6",
            baseUrl = AnthropicProvider.DEFAULT_BASE_URL,
            httpClient = http,
        )
        provider.chat(ChatRequest(messages = listOf(ChatMessage.User("hi"))))
        assertEquals("sk-ant-xxx", capturedHeaders?.get("x-api-key"))
        assertEquals("2023-06-01", capturedHeaders?.get("anthropic-version"))
    }

    @Test
    fun `chat posts to v1_messages endpoint`() = runTest {
        var capturedUrl: String? = null
        val http = mockAnthropicHttpClient { request ->
            capturedUrl = request.url.toString()
            respond(
                content = ByteReadChannel(
                    """{"id":"m1","model":"claude-sonnet-4-6","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":2}}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = AnthropicProvider(
            apiKey = "k",
            model = "claude-sonnet-4-6",
            baseUrl = AnthropicProvider.DEFAULT_BASE_URL,
            httpClient = http,
        )
        provider.chat(ChatRequest(messages = listOf(ChatMessage.User("hi"))))
        assertTrue(capturedUrl!!.endsWith("/v1/messages"), "expected /v1/messages, got $capturedUrl")
    }

    @Test
    fun `chat returns ChatResponse with assistant text`() = runTest {
        val http = mockAnthropicHttpClient { _ ->
            respond(
                content = ByteReadChannel(
                    """{"id":"m1","model":"claude-sonnet-4-6","content":[{"type":"text","text":"hello back"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":2}}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = AnthropicProvider(
            apiKey = "k",
            model = AnthropicProvider.DEFAULT_MODEL,
            baseUrl = AnthropicProvider.DEFAULT_BASE_URL,
            httpClient = http,
        )
        val response = provider.chat(ChatRequest(messages = listOf(ChatMessage.User("hi"))))
        assertEquals("hello back", response.message.content)
        assertEquals(0, response.message.toolCalls.size)
    }

    @Test
    fun `chat wraps HTTP 500 in AgentException LlmError`() = runTest {
        val http = mockAnthropicHttpClient { _ ->
            respond(
                content = ByteReadChannel("server error"),
                status = HttpStatusCode.InternalServerError,
            )
        }
        val provider = AnthropicProvider(
            apiKey = "k",
            model = "m",
            baseUrl = AnthropicProvider.DEFAULT_BASE_URL,
            httpClient = http,
        )
        try {
            provider.chat(ChatRequest(messages = listOf(ChatMessage.User("hi"))))
            error("should have thrown")
        } catch (e: AgentException.LlmError) {
            assertTrue(
                e.message!!.contains("500") || (e.cause?.message?.contains("500") ?: false),
                "expected '500' in message or cause, got: ${e.message} | cause=${e.cause?.message}",
            )
        }
    }
}
