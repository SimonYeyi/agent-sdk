package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.core.llm.ChatMessage
import io.github.yeyi.agent.core.llm.ChatRequest
import io.github.yeyi.agent.core.llm.FinishReason
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAiClientTest {

    private fun mockHttpClient(responseJson: String, statusCode: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(responseJson),
                status = statusCode,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        return HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

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
            httpClient = mockHttpClient(raw)
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
            httpClient = mockHttpClient("server error", HttpStatusCode.InternalServerError)
        )
        try {
            client.chat(ChatRequest(messages = listOf(ChatMessage.User("hi"))))
            error("should have thrown")
        } catch (e: io.github.yeyi.agent.core.error.AgentException.LlmError) {
            assertEquals(true, e.message!!.contains("LLM call failed"))
        }
    }
}
