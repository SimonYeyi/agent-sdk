package io.github.yeyi.agent

public sealed class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    public class MaxIterations(public val max: Int) :
        AgentException("Reached max iterations ($max) without final answer")

    public class LlmError(cause: Throwable) :
        AgentException("LLM call failed: ${cause.message}", cause)

    public class InvalidResponse(public val reason: String) :
        AgentException("Invalid LLM response: $reason")

    public class ToolNotFound(public val name: String, public val available: List<String>) :
        AgentException("Tool '$name' not found. Available: $available")

    public class Cancelled : AgentException("Agent run was cancelled")

    public companion object {
        /**
         * 边界处把任意 [Throwable] 抬升为 [AgentException]:
         * - 已是 [AgentException] → 原样返回(同一实例,无重复包装)
         * - 其他 → 包装为 [Wrapped] 内部子类(私有,不出现在对外 API 中)
         *
         * 用于 Agent 边界(loop catch 块)统一兜底,确保对外只暴露 [AgentException] 家族。
         */
        public fun wrap(cause: Throwable): AgentException =
            cause as? AgentException ?: Wrapped(cause)
    }

    /**
     * 内部包装类型,仅通过 [wrap] 工厂构造。
     *
     * 命名对齐家族成员的"过去分词作名词"约定([Cancelled] / [Wrapped])。
     * 消费者拿到的是 [AgentException] 引用,无需关心此实现。
     */
    private class Wrapped(cause: Throwable) :
        AgentException("Wrapped exception: ${cause.message ?: cause::class.simpleName}", cause)
}