package io.github.yeyi.agent.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 消息角色枚举。
 *
 * @property System 系统消息（通常放 persona 文本）
 * @property User 用户消息
 * @property Assistant LLM 回复消息
 * @property Tool 工具执行结果（tool result 反馈给 LLM）
 */
public enum class Role { System, User, Assistant, Tool }

/** LLM 生成的工具调用请求。 */
@Serializable
public data class ToolCall(
    public val id: String,
    public val name: String,
    public val arguments: JsonElement
)

public sealed interface ChatMessage {
    public val role: Role

    /** 系统消息，通常放入 [Persona] 渲染后的角色文本。 */
    @Serializable
    public data class System(public val content: String) : ChatMessage {
        override val role: Role = Role.System
    }

    /** 用户消息。 */
    @Serializable
    public data class User(public val content: String) : ChatMessage {
        override val role: Role = Role.User
    }

    /**
     * LLM 回复消息。
     *
     * @param content 文字回复（可能为 null，此时 [toolCalls] 非空）
     * @param toolCalls LLM 决定调用的工具列表（可能为空）
     */
    @Serializable
    public data class Assistant(
        public val content: String? = null,
        public val toolCalls: List<ToolCall> = emptyList()
    ) : ChatMessage {
        override val role: Role = Role.Assistant
    }

    /**
     * 工具执行结果，写入 memory 后反馈给 LLM。
     *
     * @param toolCallId 对应 [ToolCall.id]
     * @param toolName 工具名称
     * @param content 执行结果文本
     * @param isError 是否为错误结果
     */
    @Serializable
    public data class ToolResult(
        public val toolCallId: String,
        public val toolName: String,
        public val content: String,
        public val isError: Boolean = false
    ) : ChatMessage {
        override val role: Role = Role.Tool
    }
}
