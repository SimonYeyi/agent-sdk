package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.Usage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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
        assertEquals(ChatResponseEvent.ContentDelta("hi"), events[0])
    }

    @Test
    fun `input_json_delta maps to ToolCallDelta with argumentsDelta`() = runTest {
        val lines = flowOf(
            "event: content_block_start",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"search","input":{}}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}""",
            "",
        )
        val events = decodeAnthropicSse(lines).toList()
        assertEquals(1, events.size)
        val ev = events[0]
        assertTrue(ev is ChatResponseEvent.ToolCallDelta)
        val delta = ev as ChatResponseEvent.ToolCallDelta
        assertEquals("toolu_1", delta.id)
        assertEquals("search", delta.name)
        assertEquals("{\"city\":", delta.argumentsDelta)
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
        val done = events.filterIsInstance<ChatResponseEvent.Done>()
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
    fun `malformed json emits Error event`() = runTest {
        val lines = flowOf(
            """event: message_start""",
            """data: {not valid json""",
            "",
            """event: message_stop""",
            """data: {"type":"message_stop"}""",
            "",
        )
        val events = decodeAnthropicSse(lines).toList()
        assertTrue(events.any { it is ChatResponseEvent.Error })
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
        val done = events.last() as ChatResponseEvent.Done
        assertEquals(Usage(promptTokens = 10, completionTokens = 3, totalTokens = 13), done.usage)
        assertEquals(FinishReason.Stop, done.finishReason)
    }

    @Test
    fun `Done usage is null when neither message_start nor message_delta carries usage`() = runTest {
        val lines = flowOf(
            "event: message_start",
            """data: {"type":"message_start","message":{"id":"m1"}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""",
            "",
            "event: message_delta",
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            "",
            "event: message_stop",
            """data: {"type":"message_stop"}""",
            "",
        )
        val events = decodeAnthropicSse(lines).toList()
        val done = events.last() as ChatResponseEvent.Done
        assertEquals(null, done.usage)
        assertEquals(FinishReason.Stop, done.finishReason)
    }

    @Test
    fun `input_json_delta without preceding tool_use fails fast`() = runTest {
        // 协议保证 input_json_delta 前必有 tool_use content_block_start;缺失即违约,decoder 用 !! 直接失败
        val lines = flowOf(
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
            "",
        )
        assertFailsWith<NullPointerException> {
            decodeAnthropicSse(lines).toList()
        }
    }

    @Test
    fun `currentToolCallId and name reset after content_block_stop so next tool_use gets own`() = runTest {
        val lines = flowOf(
            "event: content_block_start",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"first","input":{}}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"1"}}""",
            "",
            "event: content_block_stop",
            """data: {"type":"content_block_stop","index":0}""",
            "",
            "event: content_block_start",
            """data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_2","name":"second","input":{}}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"2"}}""",
            "",
            "event: content_block_stop",
            """data: {"type":"content_block_stop","index":1}""",
            "",
        )
        val events = decodeAnthropicSse(lines).toList()
        val deltas = events.filterIsInstance<ChatResponseEvent.ToolCallDelta>()
        // 每个 tool use 的首个 delta 携带自己的 name;id 来自所属 content_block
        assertEquals(2, deltas.size)
        assertEquals("toolu_1", deltas[0].id)
        assertEquals("first", deltas[0].name)
        assertEquals("1", deltas[0].argumentsDelta)
        assertEquals("toolu_2", deltas[1].id)
        assertEquals("second", deltas[1].name)
        assertEquals("2", deltas[1].argumentsDelta)
    }
}
