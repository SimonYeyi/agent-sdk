package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.core.llm.FinishReason
import io.github.yeyi.agent.core.llm.StreamEvent
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
    fun `message_stop emits Done with usage`() = runTest {
        val lines = flowOf(
            "event: message_delta",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
            "",
            "event: message_stop",
            "data: {\"type\":\"message_stop\",\"amazon-bedrock-invocationMetrics\":{}}",
            "",
        )
        val events = decodeAnthropicSse(lines).toList()
        // 第一个 message_delta 不发射内容事件(SDK 用它判断 finishReason)
        // 第二个 message_stop 发射 Done
        val done = events.filterIsInstance<StreamEvent.Done>()
        assertEquals(1, done.size)
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
}
