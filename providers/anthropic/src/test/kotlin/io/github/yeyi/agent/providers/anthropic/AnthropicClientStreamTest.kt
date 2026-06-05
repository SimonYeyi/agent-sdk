package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.StreamEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AnthropicClientStreamTest {

    @Test
    fun `chatStream returns ContentDelta events from SSE`() = runTest {
        val sseBody = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m1"}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(sseBody),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = AnthropicClient(apiKey = "k", httpClient = http)
        val events = client.chatStream(
            ChatRequest(messages = listOf(ChatMessage.User("hi")))
        ).toList()
        val deltas = events.filterIsInstance<StreamEvent.ContentDelta>()
        assertEquals(1, deltas.size)
        assertEquals("hi", deltas[0].text)
    }

    @Test
    fun `chatStream emits Done event at end`() = runTest {
        val sseBody = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m1"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"x"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(sseBody),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = AnthropicClient(apiKey = "k", httpClient = http)
        val events = client.chatStream(
            ChatRequest(messages = listOf(ChatMessage.User("hi")))
        ).toList()
        val done = events.filterIsInstance<StreamEvent.Done>()
        assertEquals(1, done.size)
    }
}
