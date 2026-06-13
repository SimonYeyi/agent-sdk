package io.github.yeyi.agent

public sealed class AgentException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {
    public class MaxIterations(public val max: Int) :
        AgentException("Reached max iterations ($max) without final answer")

    public class LlmError(cause: Throwable) :
        AgentException("LLM call failed: ${cause.message}", cause)

    public class InvalidResponse(public val reason: String) :
        AgentException("Invalid LLM response: $reason")

    public class ToolNotFound(public val name: String, public val available: List<String>) :
        AgentException("Tool '$name' not found. Available: $available")

    public class Unknown(cause: Throwable) :
        AgentException(cause.message ?: cause::class.simpleName ?: "Unknown", cause)
}

internal fun Throwable.toAgentException(): AgentException {
    return this as? AgentException ?: AgentException.Unknown(this)
}