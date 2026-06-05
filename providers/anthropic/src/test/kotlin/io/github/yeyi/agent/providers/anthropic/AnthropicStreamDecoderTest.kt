package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.Usage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnthropicStreamDecoderTest {

    @Test
    fun `text_delta maps to ContentDelta`() = runTest {
        val lines = flowOf(
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}",
            "",
        )
        val events = decodeAnthropicSse(lines).toList()
        assertEquals(1, events.size)
        assertEquals(StreamEvent.ContentDelta("hi"), events[0])
    }

    @Test
    fun `input_json_delta maps to ToolCallDelta with argumentsDelta`() = runTest {
        val lines = flowOf(
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}}",
            "",
        )
        val events = decodeAnthropicSse(lines).toList()
        assertEquals(1, events.size)
        val ev = events[0]
        assertTrue(ev is StreamEvent.ToolCallDelta)
        assertEquals("{\"city\":", (ev as StreamEvent.ToolCallDelta).argumentsDelta)
    }

    @Test
    fun `message_stop emits Done`() = runTest {
        val lines = flowOf(
            "event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"usage\":{\"input_tokens\":7,\"output_tokens\":0}}}",
            "",
            "event: message_delta",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
            "",
            "event: message_stop",
            "data: {\"type\":\"message_stop\",\"amazon-bedrock-invocationMetrics\":{}}",
            "",
        )
        val events = decodeAnthropicSse(lines).toList()
        val done = events.filterIsInstance<StreamEvent.Done>()
        assertEquals(1, done.size)
        assertEquals(Usage(promptTokens = 7, completionTokens = 0, totalTokens = 7), done[0].usage)
        assertEquals(FinishReason.Stop, done[0].finishReason)
    }

    @Test
    fun `ping and message_start events are ignored`() = runTest {
        val lines = flowOf(
            "event: ping",
            "data: {\"type\":\"ping\"}",
            "",
            "event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\"}}",
            "",
        )
        val events = decodeAnthropicSse(lines).toList()
        assertEquals(0, events.size)
    }

    @Test
    fun `malformed json emits Error event and continues`() = runTest {
        val lines = flowOf(
            """event: message_start""",
            """data: {not valid json""",
            "",
            """event: message_stop""",
            """data: {"type":"message_stop"}""",
            "",
        )
        val events = decodeAnthropicSse(lines).toList()
        assertTrue(events.any { it is StreamEvent.Error })
    }

    @Test
    fun `Done carries usage from message_start and message_delta`() = runTest {
        val lines = flowOf(
            "event: message_start",
            """data: {"type":"message_start","message":{"id":"m1","usage":{"input_tokens":10,"output_tokens":0}}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""",
            "",
            "event: message_delta",
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":10,"output_tokens":3}}""",
            "",
            "event: message_stop",
            """data: {"type":"message_stop"}""",
            "",
        )
        val events = decodeAnthropicSse(lines).toList()
        val done = events.last() as StreamEvent.Done
        assertEquals(Usage(promptTokens = 10, completionTokens = 3, totalTokens = 13), done.usage)
        assertEquals(FinishReason.Stop, done.finishReason)
    }
}
