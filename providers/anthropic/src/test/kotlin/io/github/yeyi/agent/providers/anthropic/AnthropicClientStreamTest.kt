package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.Usage
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
        val http = mockAnthropicHttpClient { respond(sseBody, HttpStatusCode.OK, sseHeaders) }
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
        val http = mockAnthropicHttpClient { respond(sseBody, HttpStatusCode.OK, sseHeaders) }
        val client = AnthropicClient(apiKey = "k", httpClient = http)
        val events = client.chatStream(
            ChatRequest(messages = listOf(ChatMessage.User("hi")))
        ).toList()
        val done = events.filterIsInstance<StreamEvent.Done>()
        assertEquals(1, done.size)
    }

    @Test
    fun `chatStream emits ToolCallStart and ToolCallDelta for tool_use block`() = runTest {
        val sse = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m1","usage":{"input_tokens":5,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"calc","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"a\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"1}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"input_tokens":5,"output_tokens":3}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        val client = AnthropicClient(
            apiKey = "k", model = "m",
            httpClient = mockAnthropicHttpClient { respond(sse, HttpStatusCode.OK, sseHeaders) }
        )
        val events = client.chatStream(ChatRequest(messages = listOf(ChatMessage.User("hi")))).toList()
        val starts = events.filterIsInstance<StreamEvent.ToolCallStart>()
        val deltas = events.filterIsInstance<StreamEvent.ToolCallDelta>()
        val done = events.filterIsInstance<StreamEvent.Done>().last()
        assertEquals(1, starts.size)
        assertEquals("toolu_1", starts[0].id)
        assertEquals("calc", starts[0].name)
        assertEquals(2, deltas.size)
        assertEquals(FinishReason.ToolCalls, done.finishReason)
        assertEquals(Usage(5, 3, 8), done.usage)
    }
}
