package io.github.yeyi.agent

/**
 * Agent 领域异常体系，所有公开异常均继承自此 sealed class。
 *
 * SDK 内部边界（ReActAgent.loop）会将任意非 [kotlinx.coroutines.CancellationException] 的
 * Throwable 统一通过 [toAgentException] 抬升为此体系成员，确保对外只暴露领域异常。
 */
public sealed class AgentException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    override val message: String = super.message!!

    /** 达到最大迭代次数仍未得到最终回复。 */
    public class MaxIterations(public val max: Int) :
        AgentException("Reached max iterations ($max) without final answer")

    /** LLM 调用失败（网络错误、API 错误、超时等）。 */
    public class LlmError(cause: Throwable) :
        AgentException("LLM call failed: ${cause.message}", cause)

    /** LLM 返回了无法解析的响应格式。 */
    public class InvalidResponse(public val reason: String) :
        AgentException("Invalid LLM response: $reason")

    /** 请求调用的工具未注册。 */
    public class ToolNotFound(name: String, available: Iterable<String>) :
        AgentException("Tool '$name' not found. Available: $available") {}

    /** 未知异常兜底。 */
    public class Unknown(cause: Throwable) :
        AgentException(cause.message ?: cause::class.simpleName ?: "Unknown", cause)
}

internal fun Throwable.toAgentException(): AgentException {
    return this as? AgentException ?: AgentException.Unknown(this)
}