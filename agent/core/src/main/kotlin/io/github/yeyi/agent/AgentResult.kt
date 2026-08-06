package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * Agent 执行最终结果，包含最终回复、总迭代次数、所有工具调用记录及 token 使用量。
 *
 * @param message LLM 最终回复的 Assistant 消息
 * @param iterations 实际执行的迭代轮数（等于 LLM 调用次数）
 * @param toolCalls 所有工具调用的审计记录，按调用顺序排列
 * @param usage LLM token 使用量，部分 provider 可能为 null
 */
public data class AgentResult(
    public val message: ChatMessage.Assistant,
    public val iterations: Int,
    public val toolCalls: List<ToolCallRecord>,
    public val usage: Usage? = null,
) {
    /**
     * 单次工具调用的审计记录。
     *
     * @param callId LLM 生成的调用 ID
     * @param toolName 工具名称
     * @param arguments LLM 传来的参数字符串（JSON 树结构）
     * @param result 工具执行结果
     * @param timestamp 调用发生的时间戳
     */
    public data class ToolCallRecord(
        public val callId: String,
        public val toolName: String,
        public val arguments: JsonElement,
        public val result: ToolExecutionResult,
        public val timestamp: Instant,
    )
}
