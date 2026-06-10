package io.github.yeyi.agent.app.vm

import io.github.yeyi.agent.tool.ToolExecutionResult

sealed class UiMessage {
    abstract val id: String

    /**
     * 用户输入。[id] 由 [io.github.yeyi.agent.app.vm.ChatViewModel] 显式注入
     * 单调计数器生成——同一段文本多次发送,id 也必须不同,否则 LazyColumn
     * 第二次进时 `Key already used` 崩溃。
     */
    data class User(val text: String, override val id: String) : UiMessage()

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

    /**
     * 错误提示。[id] 由 [io.github.yeyi.agent.app.vm.ChatViewModel] 显式注入
     * 单调计数器生成——同样的错误信息(如 DNS 失败、连接超时)会在多次请求
     * 中重复出现,基于 cause.hashCode() 的 id 第二次起会冲突,改用计数器
     * 保证全 VM 生命周期内唯一。
     */
    data class Error(val cause: String, override val id: String) : UiMessage()
}
