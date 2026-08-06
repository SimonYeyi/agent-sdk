package io.github.yeyi.agent.tool

/**
 * 工具执行结果的返回值。
 *
 * 约定：[execute][Tool.execute] 应返回此对象，而非抛出异常。
 * 业务错误（如参数无效、权限不足）应设置 [isError]=true，内容放 [content]。
 * 真正需要中断执行的异常（如网络超时、资源不可用）才应抛出。
 *
 * @param content 执行结果文本，会被拼入 [io.github.yeyi.agent.llm.ChatMessage.ToolResult] 回传给 LLM
 * @param isError 是否为错误结果；为 true 时 LLM 会收到明确的错误信号
 */
public data class ToolExecutionResult(
    public val content: String,
    public val isError: Boolean = false
) {
    public companion object {
        /** 快捷构造：成功结果 */
        public fun success(content: String): ToolExecutionResult {
            return ToolExecutionResult(content)
        }

        /** 快捷构造：错误结果，等价于 `ToolExecutionResult(message, isError = true)` */
        public fun error(message: String): ToolExecutionResult {
            return ToolExecutionResult(message, isError = true)
        }
    }
}
