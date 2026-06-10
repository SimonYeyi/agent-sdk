package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.error.AgentException
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

// Forward-compat: silently ignore unknown JSON fields returned by OpenAI
// (e.g., new provider-side fields) so the parser does not break.
private val Mapper: Json = Json { ignoreUnknownKeys = true }

private val EmptyParams: JsonElement = Mapper.parseToJsonElement("""{"type":"object","properties":{}}""")

internal fun mapToOpenAi(model: String, request: ChatRequest, stream: Boolean): OpenAiChatRequest {
    val msgs = request.messages.map { msg ->
        when (msg) {
            is ChatMessage.System -> OpenAiMessage(role = "system", content = msg.content)
            is ChatMessage.User -> OpenAiMessage(role = "user", content = msg.content)
            is ChatMessage.Assistant -> OpenAiMessage(
                role = "assistant",
                content = msg.content,
                toolCalls = if (msg.toolCalls.isEmpty()) null else msg.toolCalls.map { tc ->
                    OpenAiToolCall(
                        id = tc.id,
                        function = OpenAiFunctionCall(
                            name = tc.name,
                            arguments = Mapper.encodeToString(tc.arguments)
                        )
                    )
                }
            )
            is ChatMessage.ToolResult -> OpenAiMessage(
                role = "tool",
                content = msg.content,
                toolCallId = msg.toolCallId,
                name = msg.toolName
            )
        }
    }
    val tools = if (request.tools.isEmpty()) null else request.tools.map { td ->
        val params = when (val s = td.parametersSchema) {
            is ToolParameters.Empty -> EmptyParams
            is ToolParameters.JsonSchema -> Mapper.parseToJsonElement(s.schema)
        }
        OpenAiTool(function = OpenAiFunction(
            name = td.name,
            description = td.description,
            parameters = params
        ))
    }
    return OpenAiChatRequest(
        model = model,
        messages = msgs,
        tools = tools,
        temperature = request.temperature,
        maxTokens = request.maxTokens,
        stop = request.stopSequences.takeIf { it.isNotEmpty() },
        stream = if (stream) true else null,
        streamOptions = if (stream) OpenAiStreamOptions(includeUsage = true) else null
    )
}

internal fun mapFromOpenAi(resp: OpenAiChatResponse): ChatResponse {
    val choice = resp.choices.firstOrNull()
        ?: throw AgentException.InvalidResponse("OpenAI response has no choices")
    val toolCalls = choice.message.toolCalls?.map { tc ->
        ToolCall(
            id = tc.id,
            name = tc.function.name,
            arguments = if (tc.function.arguments.isBlank()) JsonNull
                else Mapper.parseToJsonElement(tc.function.arguments)
        )
    } ?: emptyList()
    val assistant = ChatMessage.Assistant(
        content = choice.message.content,
        toolCalls = toolCalls
    )
    val usage = resp.usage?.let { Usage(it.promptTokens, it.completionTokens, it.totalTokens) }
    // OpenAI omits finish_reason only in mid-stream chunks; on the final non-streamed
    // response it is always present. Treating null and any unknown string as Stop is the
    // "unknown = safe default" rule shared across all 4 finishReason-mapping paths.
    val reason = when (choice.finishReason) {
        "stop" -> FinishReason.Stop
        "tool_calls", "function_call" -> FinishReason.ToolCalls
        "length" -> FinishReason.Length
        else -> FinishReason.Stop
    }
    return ChatResponse(message = assistant, usage = usage, finishReason = reason)
}
