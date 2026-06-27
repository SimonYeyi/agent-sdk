package io.github.yeyi.agent.tool

import kotlinx.serialization.json.JsonElement

public interface ToolDispatcher {

    public suspend fun dispatch(
        name: String,
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult
}