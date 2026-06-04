package io.github.yeyi.agent.providers.openai

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
}
