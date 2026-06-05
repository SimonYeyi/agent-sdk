package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.StreamEvent
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.toList
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
        } catch (e: io.github.yeyi.agent.error.AgentException.LlmError) {
            assertEquals(true, e.message!!.contains("LLM call failed"))
        }
    }

    @Test
    fun `chatStream emits TextDelta and Done`() = runTest {
        val sseBody = """
            data: {"choices":[{"index":0,"delta":{"content":"hi"}}]}

            data: [DONE]

        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(sseBody),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = OpenAiClient(apiKey = "k", httpClient = http)
        val events = client.chatStream(
            ChatRequest(messages = listOf(ChatMessage.User("hi")))
        ).toList()
        val deltas = events.filterIsInstance<StreamEvent.ContentDelta>()
        assertEquals(1, deltas.size)
        assertEquals("hi", deltas[0].text)
    }
}
