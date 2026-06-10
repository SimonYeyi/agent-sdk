package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.StreamEvent
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiClientStreamTest {

    @Test
    fun `chatStream emits TextDelta and Done`() = runTest {
        val sseBody = """
            data: {"choices":[{"index":0,"delta":{"content":"hi"}}]}

            data: [DONE]

        """.trimIndent()
        val client = OpenAiClient(
            apiKey = "k",
            model = OpenAiClient.DEFAULT_MODEL,
            baseUrl = OpenAiClient.DEFAULT_BASE_URL,
            httpClient = mockOpenAiHttpClient { respond(sseBody, HttpStatusCode.OK, sseHeaders) },
        )
        val events = client.chatStream(
            ChatRequest(messages = listOf(ChatMessage.User("hi")))
        ).toList()
        val deltas = events.filterIsInstance<StreamEvent.ContentDelta>()
        assertEquals(1, deltas.size)
        assertEquals("hi", deltas[0].text)
    }

    @Test
    fun `chatStream request body includes stream_options include_usage true`() = runTest {
        val sseBody = """data: [DONE]"""
        var capturedBody: String? = null
        val client = OpenAiClient(
            apiKey = "k",
            model = OpenAiClient.DEFAULT_MODEL,
            baseUrl = OpenAiClient.DEFAULT_BASE_URL,
            httpClient = mockOpenAiHttpClient { request ->
                capturedBody = captureTextBody(request)
                respond(sseBody, HttpStatusCode.OK, sseHeaders)
            },
        )
        client.chatStream(ChatRequest(messages = listOf(ChatMessage.User("hi")))).toList()
        assertTrue(capturedBody != null, "request body should have been captured")
        val json = Json.parseToJsonElement(capturedBody!!).jsonObject
        val streamOptions = json["stream_options"]?.jsonObject
        assertTrue(streamOptions != null, "stream_options must be present in streaming request")
        assertEquals(true, streamOptions["include_usage"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `chat (non-stream) request body omits stream_options`() = runTest {
        var capturedBody: String? = null
        val client = OpenAiClient(
            apiKey = "k",
            model = OpenAiClient.DEFAULT_MODEL,
            baseUrl = OpenAiClient.DEFAULT_BASE_URL,
            httpClient = mockOpenAiHttpClient { request ->
                capturedBody = captureTextBody(request)
                respond("""{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        client.chat(ChatRequest(messages = listOf(ChatMessage.User("hi"))))
        assertTrue(capturedBody != null, "request body should have been captured")
        val json = Json.parseToJsonElement(capturedBody!!).jsonObject
        // 非流式请求不应带 stream_options(避免污染非流式客户端)
        assertTrue(json["stream_options"] == null, "stream_options must be absent in non-stream request")
    }
}

private fun captureTextBody(request: HttpRequestData): String =
    (request.body as? TextContent)?.text ?: ""
