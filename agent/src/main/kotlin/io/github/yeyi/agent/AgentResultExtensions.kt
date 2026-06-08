package io.github.yeyi.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

public suspend fun Flow<AgentEvent>.awaitResult(): AgentResult =
    filterIsInstance<AgentEvent.Final>().first().result
