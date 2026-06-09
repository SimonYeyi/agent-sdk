package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.error.AgentException
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.FinishReason
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiClientChatTest {

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
        val client = OpenAiClient(
            apiKey = "test",
            model = "gpt-4o-mini",
            httpClient = mockOpenAiHttpClient { _ ->
                respond(
                    content = raw,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val resp = client.chat(ChatRequest(messages = listOf(ChatMessage.User("hi"))))
        assertEquals("hello", resp.message.content)
        assertEquals(FinishReason.Stop, resp.finishReason)
        assertEquals(5, resp.usage?.totalTokens)
    }

    @Test
    fun `chat throws LlmError on HTTP 500`() = runTest {
        val client = OpenAiClient(
            apiKey = "test",
            model = "gpt-4o-mini",
            httpClient = mockOpenAiHttpClient { _ ->
                respond(
                    content = "server error",
                    status = HttpStatusCode.InternalServerError,
                )
            },
        )
        try {
            client.chat(ChatRequest(messages = listOf(ChatMessage.User("hi"))))
            error("should have thrown")
        } catch (e: AgentException.LlmError) {
            assertTrue(e.message!!.contains("LLM call failed"))
        }
    }
}
