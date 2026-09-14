package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart

internal class RepairedMemory(private val underlying: Memory) : Memory by underlying {
    private var repaired = false

    override suspend fun add(message: ChatMessage) {
        repairIfNot()
        underlying.add(message)
    }

    override suspend fun history(): List<ChatMessage> {
        repairIfNot()
        return underlying.history()
    }

    private suspend fun repairIfNot() {
        if (repaired) return
        underlying.repairOrphans(RepairReason.CRASHED)
        repaired = true
    }
}

/**
 * 补写历史尾部末条 Assistant 消息中未配对的 toolCall，使历史重新对 LLM 合法。
 *
 * ## 语义
 *
 * 本函数**不判断中断原因**——原因由调用方通过 [reason] 显式传入：
 * - [RepairReason.CRASHED]：进程崩溃/被杀导致上次 run 无法现场收尾，下次 run 启动时修复。
 * - [RepairReason.CANCELLED]：本次 run 被协程取消，在 catch 块中当场修复。
 * - 调用方也可传入自定义文本。
 *
 * ## 不变量
 *
 * - 只检查历史末条 Assistant 消息。run 正常路径下下一条 Assistant 只会在
 *   当前批次 ToolResult 全部写入后追加，未闭合 toolCall 不可能出现在中间。
 * - 幂等：没有未配对 toolCall 时零写入；对已经补写过的末条 Assistant 再次调用不会重复。
 * - 不修改已有消息，只 append 新的 ToolResult，且统一标记 isError=true。
 */
internal suspend fun Memory.repairOrphans(reason: String) {
    val history = history()
    val lastAssistant = history.indexOfLast { it is ChatMessage.Assistant }
        .takeIf { it >= 0 } ?: return
    val assistant = history[lastAssistant] as ChatMessage.Assistant
    if (assistant.toolCalls.isEmpty()) return

    val answered = history.drop(lastAssistant + 1)
        .filterIsInstance<ChatMessage.ToolResult>()
        .mapTo(mutableSetOf()) { it.toolCallId }

    val orphans = assistant.toolCalls.filter { it.id !in answered }
    if (orphans.isEmpty()) return

    orphans.forEach { call ->
        add(
            ChatMessage.ToolResult(
                toolCallId = call.id,
                toolName = call.name,
                parts = listOf(ContentPart.Text(reason)),
                isError = true,
            )
        )
    }
}

/**
 * 未配对 toolCall 的补写标记文本。集中定义避免散落字面量，
 * 未来续跑逻辑也可通过前缀识别 marker 类型。
 */
internal object RepairReason {
    /** 进程崩溃/被杀。 */
    const val CRASHED: String = "[crashed: result unknown]"

    /** 用户主动取消（协程 CancellationException）。 */
    const val CANCELLED: String = "[cancelled by user]"
}
