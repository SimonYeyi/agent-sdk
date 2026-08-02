package io.github.yeyi.agent.demo.s2s

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.realtime.DelegationReply
import io.github.yeyi.agent.realtime.DelegationReply.Confirmation
import io.github.yeyi.agent.realtime.DelegationReply.Failure
import io.github.yeyi.agent.realtime.DelegationReply.Success
import io.github.yeyi.agent.realtime.IntentionClassifier
import io.github.yeyi.agent.realtime.RealtimeDelegation
import io.github.yeyi.agent.team.BossAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge

class BossDelegation(
    private val boss: BossAgent,
    private val scope: CoroutineScope
) : RealtimeDelegation {
    override val classifier: IntentionClassifier by lazy {
        LlmIntentionClassifier(capabilities, boss, scope)
    }

    override val capabilities: List<String> by lazy {
        listOf("空调控制", "座椅控制", "车窗控制", "氛围灯控制", "导航", "驾驶辅助")
    }

    private val runEvents = MutableSharedFlow<DelegationReply>(extraBufferCapacity = 64)

    override val replies: Flow<DelegationReply> = merge(
        runEvents,
        boss.continuations.mapNotNull { event ->
            when (event) {
                is AgentEvent.Final -> Success(event.result.message.content ?: "")
                is AgentEvent.Failed -> Failure(event.cause.message ?: event.cause.toString())
                else -> null
            }
        },
    )

    override suspend fun run(task: String) {
        var delegated = false
        boss.run(task).collect { event ->
            when (event) {
                is AgentEvent.ToolCallExplanation if (event.toolNames.contains("publish_task")) ->
                    delegated = true

                is AgentEvent.Final if (delegated.not()) ->
                    runEvents.emit(Confirmation(event.result.message.content ?: ""))

                is AgentEvent.Failed -> runEvents.emit(
                    Failure(
                        event.cause.message ?: event.cause.toString()
                    )
                )

                else -> Unit
            }
        }
    }
}
