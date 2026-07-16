package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import kotlinx.coroutines.channels.Channel

// ===== Types =====

public enum class BossState { WAITING, RUNNING, INPUTTING, COLLECTING }

internal class UserRound(
    val input: String,
    val channel: Channel<AgentEvent>,
)

internal class TaskState(
    val selections: List<Selection>,
    val task: String,
    val events: MutableList<AgentEvent> = mutableListOf(),
) {
    val terminal: Boolean
        get() = events.lastOrNull() is AgentEvent.Final || events.lastOrNull() is AgentEvent.Failed
}