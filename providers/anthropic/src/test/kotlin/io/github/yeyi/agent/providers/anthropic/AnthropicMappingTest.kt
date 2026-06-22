package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.ToolDefinition
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnthropicMappingTest {

    @Test
    fun `system prompt is extracted to top-level field`() {
        val req = ChatRequest(
            messages = listOf(
                ChatMessage.System("you are concise"),
                ChatMessage.User("hi"),
            ),
        )
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        assertEquals("you are concise", mapped.system)
    }

    @Test
    fun `null system prompt produces null system field`() {
        val req = ChatRequest(
            messages = listOf(ChatMessage.User("hi")),
        )
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        assertNull(mapped.system)
    }

    @Test
    fun `multiple System messages are merged into top-level system field`() {
        // Anthropic Messages API 不接受 messages 数组里出现 role="system",
        // 因此所有 System 必须在 mapping 阶段合并到顶层 system 字段, 用空行分隔。
        val req = ChatRequest(
            messages = listOf(
                ChatMessage.System("persona: helpful"),
                ChatMessage.System("summary: previous conversation"),
                ChatMessage.User("hi"),
            ),
        )
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        assertEquals("persona: helpful\n\nsummary: previous conversation", mapped.system)
        // 合并后 messages 数组里不应残留 System(2 个 System 已合并,只剩 1 条 User)
        assertEquals(1, mapped.messages.size)
    }

    @Test
    fun `User and Assistant messages are mapped with text content block`() {
        val req = ChatRequest(
            messages = listOf(
                ChatMessage.User("hello"),
                ChatMessage.Assistant(content = "hi back"),
            ),
        )
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        assertEquals(
            listOf(
                AnthropicMessage("user", listOf(AnthropicContentBlock.Text("hello"))),
                AnthropicMessage("assistant", listOf(AnthropicContentBlock.Text("hi back"))),
            ),
            mapped.messages,
        )
    }

    @Test
    fun `Assistant tool call is mapped to tool_use content block`() {
        val arguments = buildJsonObject { put("city", JsonPrimitive("Beijing")) }
        val req = ChatRequest(
            messages = listOf(
                ChatMessage.User("weather?"),
                ChatMessage.Assistant(
                    content = null,
                    toolCalls = listOf(ToolCall("call_1", "get_weather", arguments)),
                ),
            ),
        )
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        val assistantMsg = mapped.messages[1]
        assertEquals("assistant", assistantMsg.role)
        assertEquals(1, assistantMsg.content.size)
        val block = assistantMsg.content[0]
        assertEquals(AnthropicContentBlock.ToolUse("call_1", "get_weather", arguments), block)
    }

    @Test
    fun `ToolResult is mapped to user message with tool_result block`() {
        val req = ChatRequest(
            messages = listOf(
                ChatMessage.User("weather?"),
                ChatMessage.Assistant(
                    content = null,
                    toolCalls = listOf(ToolCall("call_1", "get_weather", buildJsonObject {})),
                ),
                ChatMessage.ToolResult(toolCallId = "call_1", toolName = "get_weather", content = "25C sunny", isError = false),
            ),
        )
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        val toolResultMsg = mapped.messages[2]
        assertEquals("user", toolResultMsg.role)
        assertEquals(
            AnthropicContentBlock.ToolResult("call_1", "25C sunny", isError = false),
            toolResultMsg.content[0],
        )
    }

    @Test
    fun `tools list maps ToolDefinition with input_schema field name`() {
        val schemaJson = """{"type":"object","properties":{"city":{"type":"string"}}}"""
        val req = ChatRequest(
            messages = listOf(ChatMessage.User("hi")),
            tools = listOf(
                ToolDefinition(
                    name = "get_weather",
                    description = "Get current weather",
                    parametersSchema = ToolParameters.JsonSchema(schemaJson),
                ),
            ),
        )
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        assertEquals(1, mapped.tools?.size)
        val tool = mapped.tools!![0]
        assertEquals("get_weather", tool.name)
        assertEquals("Get current weather", tool.description)
        // input_schema 是 Anthropic 特定字段名
        val expected = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf("city" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
        ))
        assertEquals(expected, tool.inputSchema)
    }

    @Test
    fun `mapToCore converts Anthropic response to ChatResponse with text`() {
        val resp = AnthropicChatResponse(
            id = "msg_1",
            model = "claude-sonnet-4-6",
            content = listOf(AnthropicContentBlock.Text("hello back")),
            stopReason = "end_turn",
            usage = AnthropicUsage(inputTokens = 10, outputTokens = 5),
        )
        val core = mapAnthropicToCore(resp)
        assertEquals(
            ChatMessage.Assistant(content = "hello back", toolCalls = emptyList()),
            core.message,
        )
        assertEquals(FinishReason.Stop, core.finishReason)
        assertEquals(15, core.usage?.totalTokens)
    }

    @Test
    fun `mapToCore converts tool_use content block to ToolCall`() {
        val arguments = buildJsonObject { put("city", JsonPrimitive("Beijing")) }
        val resp = AnthropicChatResponse(
            id = "msg_1",
            model = "claude-sonnet-4-6",
            content = listOf(
                AnthropicContentBlock.Text("let me check"),
                AnthropicContentBlock.ToolUse("toolu_1", "get_weather", arguments),
            ),
            stopReason = "tool_use",
            usage = null,
        )
        val core = mapAnthropicToCore(resp)
        assertEquals(1, core.message.toolCalls.size)
        val call = core.message.toolCalls[0]
        assertEquals("toolu_1", call.id)
        assertEquals("get_weather", call.name)
        assertEquals(arguments, call.arguments)
        assertEquals(FinishReason.ToolCalls, core.finishReason)
    }
}
