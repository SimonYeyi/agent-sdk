package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent

internal interface Beast {
    suspend fun run(task: String, onEvent: suspend (AgentEvent) -> Unit)
}