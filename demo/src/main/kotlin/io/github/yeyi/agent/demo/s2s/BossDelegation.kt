package io.github.yeyi.agent.demo.s2s

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.realtime.DelegationReply
import io.github.yeyi.agent.realtime.DelegationReply.Confirmation
import io.github.yeyi.agent.realtime.DelegationReply.Failure
import io.github.yeyi.agent.realtime.DelegationReply.Success
import io.github.yeyi.agent.realtime.RealtimeDelegation
import io.github.yeyi.agent.team.BossAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge

class BossDelegation(private val boss: BossAgent) : RealtimeDelegation {
    override val capabilities: List<String> by lazy {
        listOf("空调控制", "窗帘控制")
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

    override suspend fun run(asrText: String) {
        boss.run(asrText).collect { event ->
            when (event) {
                is AgentEvent.Final -> runEvents.emit(
                    Confirmation(event.result.message.content ?: "")
                )

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
