package io.github.yeyi.agent.tool

public data class ToolExecutionResult(
    public val content: String,
    public val isError: Boolean = false
) {
    public companion object {
        public fun success(content: String): ToolExecutionResult {
            return ToolExecutionResult(content)
        }

        public fun error(message: String): ToolExecutionResult {
            return ToolExecutionResult(message, isError = true)
        }
    }
}
