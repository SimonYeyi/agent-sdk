package io.github.yeyi.agent.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

public enum class Role { System, User, Assistant, Tool }

@Serializable
public data class ToolCall(
    public val id: String,
    public val name: String,
    public val arguments: JsonElement
)

public sealed interface ChatMessage {
    public val role: Role

    @Serializable
    public data class System(public val content: String) : ChatMessage {
        override val role: Role = Role.System
    }

    @Serializable
    public data class User(public val content: String) : ChatMessage {
        override val role: Role = Role.User
    }

    @Serializable
    public data class Assistant(
        public val content: String? = null,
        public val toolCalls: List<ToolCall> = emptyList()
    ) : ChatMessage {
        override val role: Role = Role.Assistant
    }

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
