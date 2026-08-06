package io.github.yeyi.agent.tool

public sealed interface ToolParameters {
    public object Empty : ToolParameters
    public data class JsonSchema(public val schema: String) : ToolParameters
}
