package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ContentPart
import kotlinx.serialization.Serializable

/**
 * Agent 入口的"用户回合"包装：把文本 + 多模态块以出现顺序一次性提交。
 *
 * 与 ChatMessage.User 不互替：前者是 Agent 层输入视角，后者是 LLM/Memory
 * 层消息视角；通过 ChatMessage.User(query.parts) 互转。
 *
 * 不预留 metadata：当前没有 traceId / sessionContext 等需求；将来要加时
 * 走外层扩展，不动 data class 字段，避免变成垃圾桶。
 */
@Serializable
public data class AgentQuery(public val parts: List<ContentPart>) {
    init {
        require(parts.isNotEmpty()) { "AgentQuery.parts must not be empty" }
    }

    public companion object {
        /** 纯文本便捷入口。 */
        public fun text(content: String): AgentQuery =
            AgentQuery(listOf(ContentPart.Text(content)))
    }
}
