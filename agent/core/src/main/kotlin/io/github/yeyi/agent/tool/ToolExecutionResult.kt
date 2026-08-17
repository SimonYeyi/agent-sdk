package io.github.yeyi.agent.tool

import io.github.yeyi.agent.llm.ContentPart

/**
 * 工具执行结果的返回值。
 *
 * 约定：[execute][Tool.execute] 应返回此对象，而非抛出异常。
 * 业务错误（如参数无效、权限不足）应设置 [isError]=true，内容放 [parts]。
 * 真正需要中断执行的异常（如网络超时、资源不可用）才应抛出。
 *
 * 工厂方法 [success] / [error] 保证 [parts] 非空，建议优先使用。
 *
 * @param parts 执行结果内容块（文本 + image/audio/video），会被拼入
 *   [io.github.yeyi.agent.llm.ChatMessage.ToolResult] 回传给 LLM
 * @param isError 是否为错误结果；为 true 时 LLM 会收到明确的错误信号
 */
public data class ToolExecutionResult(
    public val parts: List<ContentPart>,
    public val isError: Boolean
) {
    public companion object {
        /** 快捷构造：纯文本成功结果 */
        public fun success(content: String): ToolExecutionResult =
            ToolExecutionResult(listOf(ContentPart.Text(content)), isError = false)

        /** 快捷构造：多模态成功结果 */
        public fun success(parts: List<ContentPart>): ToolExecutionResult =
            ToolExecutionResult(parts, isError = false)

        /** 快捷构造：文本描述 + 媒体块的成功结果，文本排在首位 */
        public fun success(content: String, parts: List<ContentPart>): ToolExecutionResult =
            ToolExecutionResult(listOf(ContentPart.Text(content)) + parts, isError = false)

        /** 快捷构造：纯文本错误结果 */
        public fun error(message: String): ToolExecutionResult =
            ToolExecutionResult(listOf(ContentPart.Text(message)), isError = true)
    }
}
