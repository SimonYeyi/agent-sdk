package io.github.yeyi.agent.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

public interface RealtimeDelegation {
    public val classifier: IntentionClassifier? get() = null
    public val capabilities: List<String>
    public val replies: Flow<DelegationReply>
    public suspend fun run(task: String)
}

public interface IntentionClassifier {
    public suspend fun classify(asr: String): Intention
}

public sealed interface Intention {
    public data class Delegated(val ack: String, val task: String) : Intention
    public data class Casual(val ack: String?) : Intention
}

public val Intention?.ack: String?
    get() = when (this) {
        is Intention.Delegated -> ack
        is Intention.Casual -> ack
        null -> null
    }

public sealed interface DelegationReply {
    public data class Confirmation(val text: String) : DelegationReply
    public data class Success(val text: String) : DelegationReply
    public data class Failure(val message: String) : DelegationReply
}

internal class DelegationProcessor(
    private val delegation: RealtimeDelegation,
    private val scopeProvider: () -> CoroutineScope?,
    private val onReply: suspend (String) -> Unit,
    private val onReplacementAck: suspend (String) -> Unit,
) {
    private val strategy = delegation.classifier
        ?.let { OuterClassifyStrategy(it, onReplacementAck) }
        ?: InnerClassifyStrategy(delegation.capabilities)

    private val runDelegation: (String) -> Unit = { task ->
        scopeProvider()?.launch { delegation.run(task) }
    }

    fun appendInstructions(base: String): String = strategy.appendInstructions(base)

    fun start() {
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

    suspend fun process(event: RealtimeEvent): RealtimeEvent? =
        strategy.process(event, runDelegation)


    private sealed interface Strategy {
        fun appendInstructions(base: String): String
        suspend fun process(
            event: RealtimeEvent,
            runDelegation: (task: String) -> Unit,
        ): RealtimeEvent?
    }

    private class InnerClassifyStrategy(private val capabilities: List<String>) : Strategy {
        private var pendingAsr: String? = null

        override fun appendInstructions(base: String): String {
            val capabilityList = capabilities.joinToString("\n") { "- $it" }
            return "$base\n\n${
                DELEGATION_PROTOCOL.replace(
                    CAPABILITIES_PLACEHOLDER,
                    capabilityList
                )
            }"
        }

        override suspend fun process(
            event: RealtimeEvent,
            runDelegation: (task: String) -> Unit,
        ): RealtimeEvent {
            when (event) {
                is RealtimeEvent.UserTranscriptCompleted -> {
                    pendingAsr = event.text
                    return event
                }

                is RealtimeEvent.AssistantTextDelta -> {
                    if (event.text.startsWith(DELEGATION_MARKER)) {
                        pendingAsr?.let { asr -> runDelegation(asr) }
                        return event.copy(text = event.text.removePrefix(DELEGATION_MARKER))
                    }
                    return event
                }

                else -> return event
            }
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

    private class OuterClassifyStrategy(
        private val classifier: IntentionClassifier,
        private val onReplacementAck: suspend (String) -> Unit,
    ) : Strategy {
        private var currentRoundIntent: Intention? = null
        private val shouldSuppressTts get() = currentRoundIntent?.ack != null

        override fun appendInstructions(base: String): String = base

        override suspend fun process(
            event: RealtimeEvent,
            runDelegation: (task: String) -> Unit,
        ): RealtimeEvent? {
            when (event) {
                is RealtimeEvent.UserTranscriptStarted -> {
                    currentRoundIntent = null
                    return event
                }

                is RealtimeEvent.UserTranscriptCompleted -> {
                    val intent = try {
                        classifier.classify(event.text)
                    } catch (_: Throwable) {
                        Intention.Casual(null)
                    }
                    if (intent is Intention.Delegated) {
                        runDelegation(intent.task)
                    }
                    runCatching { intent.ack?.let { ack -> onReplacementAck(ack) } }
                    currentRoundIntent = intent
                    return event
                }

                is RealtimeEvent.AssistantTextDelta,
                is RealtimeEvent.AssistantAudioStarted,
                is RealtimeEvent.AssistantAudioDelta,
                is RealtimeEvent.AssistantAudioDone,
                is RealtimeEvent.ResponseDone,
                is RealtimeEvent.ResponseCanceled,
                is RealtimeEvent.Error -> {
                    if (shouldSuppressTts) {
                        if (event is RealtimeEvent.ResponseCanceled
                            || event is RealtimeEvent.ResponseDone
                            || event is RealtimeEvent.Error
                        ) {
                            // 终态事件，解除压制
                            currentRoundIntent = null
                        }
                        return null
                    }
                    return event
                }

                else -> return event
            }
        }
    }
}
