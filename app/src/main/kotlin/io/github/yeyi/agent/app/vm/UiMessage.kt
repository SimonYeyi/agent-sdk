package io.github.yeyi.agent.app.vm

import io.github.yeyi.agent.tool.ToolExecutionResult

sealed class UiMessage {
    abstract val id: String

    data class User(val text: String) : UiMessage() {
        override val id: String = "u-${text.hashCode()}"
    }

    /**
     * 已落定的 assistant 文本。
     *
     * [id] 显式构造——流式期间 `liveBubble` 用 sentinel id("a-live-{turn}"),
     * Final 提交时沿用同 id,保证 LazyColumn 视为同 item 原地更新,避免
     * "删 live + 加 Assistant" 的视觉跳动。
     */
    data class Assistant(val text: String, override val id: String) : UiMessage()

    data class ToolInProgress(
        val callId: String,
        val toolName: String,
    ) : UiMessage() {
        override val id: String = "ip-$callId"
    }

    data class ToolExecution(
        val callId: String,
        val toolName: String,
        val result: ToolExecutionResult,
    ) : UiMessage() {
        override val id: String = "ex-$callId"
    }

    data class Error(val cause: String) : UiMessage() {
        override val id: String = "e-${cause.hashCode()}"
    }
}
