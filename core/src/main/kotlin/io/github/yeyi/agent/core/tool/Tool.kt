package io.github.yeyi.agent.core.tool

import kotlinx.serialization.json.JsonElement

public interface Tool {
    public val name: String
    public val description: String
    public val parametersSchema: ToolParameters
    public suspend fun execute(args: JsonElement, ctx: ToolContext): ToolExecutionResult
}
