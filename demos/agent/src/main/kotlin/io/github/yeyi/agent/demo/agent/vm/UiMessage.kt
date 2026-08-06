package io.github.yeyi.agent.demo.agent.vm

import io.github.yeyi.agent.tool.ToolExecutionResult

sealed class UiMessage {
    abstract val id: String

    /** [id] 由 [ChatViewModel] 注入,VM 生命周期内唯一,作 LazyColumn key 用。 */
    data class User(val text: String, override val id: String) : UiMessage()

    /**
     * 已落定的 assistant 文本。
     *
     * [id] 沿用流式期间的 LiveBubble.id,保证 LazyColumn 视为同 item
     * 原地更新,避免"删 live + 加 Assistant"的视觉跳动。BATCH 模式无
     * live bubble 时,id 由 [ChatViewModel] 显式生成。
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

    /** [id] 由 [ChatViewModel] 注入,VM 生命周期内唯一,作 LazyColumn key 用。 */
    data class Error(val cause: String, override val id: String) : UiMessage()
}
