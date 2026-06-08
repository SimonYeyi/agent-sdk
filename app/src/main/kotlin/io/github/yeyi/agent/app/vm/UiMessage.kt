package io.github.yeyi.agent.app.vm

import io.github.yeyi.agent.ToolCallRecord

sealed class UiMessage {
    abstract val id: String

    data class User(val text: String) : UiMessage() {
        override val id: String = "u-${text.hashCode()}"
    }

    data class Assistant(val text: String) : UiMessage() {
        override val id: String = "a-${text.hashCode()}"
    }

    data class ToolInProgress(
        val callId: String,
        val toolName: String,
    ) : UiMessage() {
        override val id: String = "ip-$callId"
    }

    data class ToolExecution(
        val callId: String,
        val record: ToolCallRecord,
    ) : UiMessage() {
        override val id: String = "ex-$callId"
    }

    data class Error(val cause: String) : UiMessage() {
        override val id: String = "e-${cause.hashCode()}"
    }
}
