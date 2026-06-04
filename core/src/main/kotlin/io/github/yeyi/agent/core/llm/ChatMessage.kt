package io.github.yeyi.agent.core.llm

import kotlinx.serialization.json.JsonElement

public enum class Role { System, User, Assistant, Tool }

public data class ToolCall(
    public val id: String,
    public val name: String,
    public val arguments: JsonElement
)

public sealed interface ChatMessage {
    public val role: Role

    public data class System(public val content: String) : ChatMessage {
        override val role: Role = Role.System
    }

    public data class User(public val content: String) : ChatMessage {
        override val role: Role = Role.User
    }

    public data class Assistant(
        public val content: String? = null,
        public val toolCalls: List<ToolCall> = emptyList()
    ) : ChatMessage {
        override val role: Role = Role.Assistant
    }

    public data class ToolResult(
        public val toolCallId: String,
        public val toolName: String,
        public val content: String,
        public val isError: Boolean = false
    ) : ChatMessage {
        override val role: Role = Role.Tool
    }
}
