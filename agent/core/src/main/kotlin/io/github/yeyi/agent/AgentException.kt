package io.github.yeyi.agent

/**
 * Agent 领域异常体系，所有公开异常均继承自此 sealed class。
 *
 * 仅用于 SDK 主动抛出的领域错误（如 [MaxIterations]、[LlmError]）；
 * 失败路径的 hook 回调 [io.github.yeyi.agent.AgentHook.onRunFailed] 与事件层
 * [AgentEvent.Failed] 均携带原始 [Throwable]，不再由边界统一抬升为 [AgentException]。
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

    /** LLM context 超限。 */
    public class ContextOverflow(
        public val reason: String,
        cause: Throwable? = null
    ) : AgentException("Context overflow: $reason", cause)

    /**
     * Provider 拒绝某种内容形态（如 OpenAI video、video base64 等）。
     * 不在 type 层静态禁止——在 provider 实现层 fail-fast, 给未来扩展留口子。
     */
    public class UnsupportedContent(message: String) : AgentException(message)
}

internal fun Throwable.isContextOverflow(): Boolean {
    if (this is AgentException.ContextOverflow) return true

    val rawMessage = buildString {
        this@isContextOverflow.message?.let { append(it) }
        this@isContextOverflow.cause?.message?.let {
            if (isNotEmpty()) append(" $it")
        }
    }.lowercase().ifEmpty { return false }

    val normalizedMessage = rawMessage.replace("_", " ")

    val patterns = listOf(
        "context length exceeded",
        "maximum context length",
        "too many tokens",
        "token limit",
        "context overflow",
        "payload too large",
        "max tokens exceeded",
        "prompt is too long",
        "input exceeds max tokens",
        "context window full",
        "input token count exceeds limit",
        "text length exceeds maximum allowed",
        "request size limit exceeded",
        "request entity too large",
        "content too large",
        "ctx exceeded",
        "sequence length exceeds configured context size",
        "kv cache full",
        "message too long",
        "exceeds the maximum number of tokens",
        "conversation history too long",
        "prompt too big",
        "input too long",
        "sequence too long",
        "tokens limit reached"
    )

    return patterns.any { it in normalizedMessage }
}