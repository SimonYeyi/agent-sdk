package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.core.error.AgentException
import io.github.yeyi.agent.core.llm.ChatMessage
import io.github.yeyi.agent.core.llm.ChatRequest
import io.github.yeyi.agent.core.llm.FinishReason
import io.github.yeyi.agent.core.llm.ToolCall
import io.github.yeyi.agent.core.llm.ToolDefinition
import io.github.yeyi.agent.core.tool.ToolParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OpenAiMappingTest {

    @Test
    fun `mapToOpenAi converts System User Assistant ToolResult`() {
        val req = ChatRequest(
            messages = listOf(
                ChatMessage.System("sys"),
                ChatMessage.User("hi"),
                ChatMessage.Assistant(content = "let me check", toolCalls = listOf(
                    ToolCall("c1", "echo", JsonObject(mapOf("text" to JsonPrimitive("x"))))
                )),
                ChatMessage.ToolResult(toolCallId = "c1", toolName = "echo", content = "x")
            )
        )
        val out = mapToOpenAi("gpt-4o-mini", req, stream = false)
        assertEquals("gpt-4o-mini", out.model)
        assertEquals(listOf("system", "user", "assistant", "tool"), out.messages.map { it.role })
        assertEquals("c1", out.messages[3].toolCallId)
        assertEquals(1, out.messages[2].toolCalls!!.size)
        val argsString = out.messages[2].toolCalls!![0].function.arguments
        // OpenAI 把 arguments 存 string
        assertEquals("""{"text":"x"}""", argsString)
    }

    @Test
    fun `mapToOpenAi serializes tools`() {
        val req = ChatRequest(
            messages = listOf(ChatMessage.User("hi")),
            tools = listOf(
                ToolDefinition(
                    name = "echo",
                    description = "Echoes",
                    parametersSchema = ToolParameters.JsonSchema("""{"type":"object"}""")
                )
            )
        )
        val out = mapToOpenAi("gpt-4o-mini", req, stream = false)
        assertEquals(1, out.tools!!.size)
        assertEquals("echo", out.tools!![0].function.name)
    }

    @Test
    fun `mapFromOpenAi decodes ChatResponse`() {
        val raw = OpenAiChatResponse(
            choices = listOf(
                OpenAiChoice(
                    message = OpenAiMessage(
                        role = "assistant",
                        content = "ok",
                        toolCalls = listOf(OpenAiToolCall(
                            id = "c1",
                            function = OpenAiFunctionCall("echo", """{"text":"x"}""")
                        ))
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = OpenAiUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
        )
        val parsed = mapFromOpenAi(raw)
        assertEquals(FinishReason.ToolCalls, parsed.finishReason)
        assertEquals("ok", parsed.message.content)
        assertEquals(1, parsed.message.toolCalls.size)
        val tc = parsed.message.toolCalls[0]
        assertEquals("c1", tc.id)
        assertEquals("echo", tc.name)
        // arguments 字符串被解回 JsonObject
        val obj = tc.arguments as JsonObject
        assertEquals(JsonPrimitive("x"), obj["text"])
    }

    @Test
    fun `mapFromOpenAi handles content null and no tool calls`() {
        val raw = OpenAiChatResponse(
            choices = listOf(OpenAiChoice(message = OpenAiMessage(role = "assistant"), finishReason = "stop"))
        )
        val parsed = mapFromOpenAi(raw)
        assertEquals(FinishReason.Stop, parsed.finishReason)
        assertNull(parsed.message.content)
        assertEquals(0, parsed.message.toolCalls.size)
    }

    @Test
    fun `mapFromOpenAi with null usage yields null usage`() {
        val raw = OpenAiChatResponse(
            choices = listOf(OpenAiChoice(message = OpenAiMessage(role = "assistant", content = "ok"), finishReason = "stop")),
            usage = null
        )
        val parsed = mapFromOpenAi(raw)
        assertNull(parsed.usage)
    }

    @Test
    fun `mapFromOpenAi decodes length finish reason`() {
        val raw = OpenAiChatResponse(
            choices = listOf(OpenAiChoice(message = OpenAiMessage(role = "assistant", content = "partial"), finishReason = "length"))
        )
        val parsed = mapFromOpenAi(raw)
        assertEquals(FinishReason.Length, parsed.finishReason)
    }

    @Test
    fun `mapFromOpenAi decodes function_call legacy finish reason`() {
        val raw = OpenAiChatResponse(
            choices = listOf(OpenAiChoice(message = OpenAiMessage(role = "assistant"), finishReason = "function_call"))
        )
        val parsed = mapFromOpenAi(raw)
        assertEquals(FinishReason.ToolCalls, parsed.finishReason)
    }

    @Test
    fun `mapFromOpenAi maps unknown finish reason to Error`() {
        val raw = OpenAiChatResponse(
            choices = listOf(OpenAiChoice(message = OpenAiMessage(role = "assistant"), finishReason = "some_new_reason_xyz"))
        )
        val parsed = mapFromOpenAi(raw)
        assertEquals(FinishReason.Error, parsed.finishReason)
    }

    @Test
    fun `mapToOpenAi serializes ToolParameters Empty as object schema`() {
        val req = ChatRequest(
            messages = listOf(ChatMessage.User("hi")),
            tools = listOf(
                ToolDefinition(
                    name = "noop",
                    description = "no params",
                    parametersSchema = ToolParameters.Empty
                )
            )
        )
        val out = mapToOpenAi("gpt-4o-mini", req, stream = false)
        val expected = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(emptyMap())
        ))
        assertEquals(expected, out.tools!![0].function.parameters as JsonObject)
    }

    @Test
    fun `mapToOpenAi sets stream true when stream param is true`() {
        val req = ChatRequest(messages = listOf(ChatMessage.User("hi")))
        val out = mapToOpenAi("gpt-4o-mini", req, stream = true)
        assertEquals(true, out.stream)
    }

    @Test
    fun `mapToOpenAi omits stop when stopSequences is empty, sets it when non-empty`() {
        val empty = ChatRequest(messages = listOf(ChatMessage.User("hi")), stopSequences = emptyList())
        val nonEmpty = ChatRequest(messages = listOf(ChatMessage.User("hi")), stopSequences = listOf("\n"))
        val outEmpty = mapToOpenAi("gpt-4o-mini", empty, stream = false)
        val outNonEmpty = mapToOpenAi("gpt-4o-mini", nonEmpty, stream = false)
        assertNull(outEmpty.stop)
        assertEquals(listOf("\n"), outNonEmpty.stop)
    }

    @Test
    fun `mapFromOpenAi with empty choices throws InvalidResponse`() {
        val raw = OpenAiChatResponse(choices = emptyList())
        assertFailsWith<AgentException.InvalidResponse> { mapFromOpenAi(raw) }
    }
}
