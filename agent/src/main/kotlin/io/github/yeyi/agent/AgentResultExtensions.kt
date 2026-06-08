package io.github.yeyi.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

public suspend fun Flow<AgentEvent>.awaitResult(): AgentResult {
    val final = filterIsInstance<AgentEvent.Final>().first()
    return AgentResult(
        finalMessage = final.message,
        iterations = final.iterations,
        toolCalls = final.toolCallRecords,
    )
}
