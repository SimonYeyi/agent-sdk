package io.github.yeyi.agent.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

public interface RealtimeDelegation {
    public val capabilities: List<String>
    public val replies: Flow<DelegationReply>
    public suspend fun run(task: String)
}

public sealed interface DelegationReply {
    public data class Confirmation(val text: String) : DelegationReply
    public data class Success(val text: String) : DelegationReply
    public data class Failure(val message: String) : DelegationReply
}

public class DelegationHandler(
    private val delegation: RealtimeDelegation,
    private val scopeProvider: () -> CoroutineScope?,
    private val onReply: suspend (String) -> Unit,
) {
    private var pendingAsr: String? = null

    public fun appendInstructions(base: String): String {
        val capabilityList = delegation.capabilities.joinToString("\n") { "- $it" }
        return "$base\n\n${DELEGATION_PROTOCOL.replace(CAPABILITIES_PLACEHOLDER, capabilityList)}"
    }

    public fun start() {
        scopeProvider()?.launch {
            delegation.replies.collect { update ->
                val text = when (update) {
                    is DelegationReply.Confirmation -> update.text
                    is DelegationReply.Success -> update.text
                    is DelegationReply.Failure -> update.message
                }
                onReply(text)
            }
        }
    }

    public fun handle(event: RealtimeEvent): RealtimeEvent {
        when (event) {
            is RealtimeEvent.UserTranscriptCompleted -> pendingAsr = event.text
            is RealtimeEvent.AssistantTextDelta -> {
                if (event.text.startsWith(DELEGATION_MARKER)) {
                    pendingAsr?.let { runDelegation(it) }
                    return event.copy(text = event.text.removePrefix(DELEGATION_MARKER))
                }
            }

            else -> Unit
        }
        return event
    }

    private fun runDelegation(asrText: String) {
        scopeProvider()?.launch { delegation.run(asrText) }
    }

    private companion object {
        private const val DELEGATION_MARKER = "|"
        private const val AVAILABLE_CAPABILITIES_LABEL = "可用能力"
        private const val CAPABILITIES_PLACEHOLDER = "CAPABILITIES_PLACEHOLDER"
        private val DELEGATION_PROTOCOL = """
            委派协议：
            1. 闲聊 (问候/聊天/知识问答/一般咨询)：直接自然口语回答。
            2. 命中已注册的 function_call 工具：直接发起函数调用，无需标记委派（跳过第3点）。
            3. 落在下面"$AVAILABLE_CAPABILITIES_LABEL"列表中（不在能力范围内，一律按闲聊处理）：
               assistant 输出**必须**以 $DELEGATION_MARKER 开头标记委派，紧接对用户的简短确认。

               完整示例（用户说"帮我调暗客厅灯"）：

                   ${DELEGATION_MARKER}好的，正在为您调暗客厅灯，请稍等

               要求:
               - 简短确认**必须用进行时** (表达"正在处理"), 不能用完成时承诺结果。

               ${AVAILABLE_CAPABILITIES_LABEL}：
               $CAPABILITIES_PLACEHOLDER
        """.trimIndent()
    }
}
