package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiProviderChatTest {

    @Test
    fun `chat returns parsed ChatResponse`() = runTest {
        val raw = """
            {
              "id":"c1",
              "choices":[
                {"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}
              ],
              "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}
            }
        """.trimIndent()
        val provider = OpenAiProvider(
            apiKey = "test",
            model = "gpt-4o-mini",
            baseUrl = OpenAiProvider.DEFAULT_BASE_URL,
            httpClient = mockOpenAiHttpClient { _ ->
                respond(
                    content = raw,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val resp = provider.chat(ChatRequest(messages = listOf(ChatMessage.User(listOf(ContentPart.Text("hi"))))))
        assertEquals("hello", resp.message.content)
        assertEquals(FinishReason.Stop, resp.finishReason)
        assertEquals(5, resp.usage?.totalTokens)
    }

    @Test
    fun `chat throws LlmError on HTTP 500`() = runTest {
        val provider = OpenAiProvider(
            apiKey = "test",
            model = "gpt-4o-mini",
            baseUrl = OpenAiProvider.DEFAULT_BASE_URL,
            httpClient = mockOpenAiHttpClient { _ ->
                respond(
                    content = "server error",
                    status = HttpStatusCode.InternalServerError,
                )
            },
        )
        try {
            provider.chat(ChatRequest(messages = listOf(ChatMessage.User(listOf(ContentPart.Text("hi"))))))
            error("should have thrown")
        } catch (e: AgentException.LlmError) {
            assertTrue(e.message!!.contains("LLM call failed"))
        }
    }

    @Test
    fun `chat sends disabled configs for all dialects when thinking false`() = runTest {
        var capturedBody: String? = null
        val provider = OpenAiProvider(
            apiKey = "test",
            model = "deepseek-chat",
            baseUrl = OpenAiProvider.DEFAULT_BASE_URL,
            thinking = false,
            httpClient = mockOpenAiHttpClient { request ->
                capturedBody = requestBodyText(request.body)
                respond(
                    content = """{"id":"c1","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        provider.chat(ChatRequest(messages = listOf(ChatMessage.User(listOf(ContentPart.Text("hi"))))))
        // 关闭时统一带上各方言关配置: DeepSeek / Kimi / GLM 认 thinking 对象, 通义千问认 enable_thinking
        assertTrue(capturedBody!!.contains(""""thinking":{"type":"disabled"}"""), "actual: $capturedBody")
        assertTrue(capturedBody!!.contains(""""enable_thinking":false"""), "actual: $capturedBody")
    }

    @Test
    fun `chat sends enabled configs for all dialects when thinking true`() = runTest {
        var capturedBody: String? = null
        val provider = OpenAiProvider(
            apiKey = "test",
            model = "qwen-plus",
            baseUrl = OpenAiProvider.DEFAULT_BASE_URL,
            thinking = true,
            httpClient = mockOpenAiHttpClient { request ->
                capturedBody = requestBodyText(request.body)
                respond(
                    content = """{"id":"c1","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        provider.chat(ChatRequest(messages = listOf(ChatMessage.User(listOf(ContentPart.Text("hi"))))))
        assertTrue(capturedBody!!.contains(""""thinking":{"type":"enabled"}"""), "actual: $capturedBody")
        assertTrue(capturedBody!!.contains(""""enable_thinking":true"""), "actual: $capturedBody")
    }
}
