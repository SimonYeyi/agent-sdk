package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ContentPart
import kotlinx.serialization.Serializable

/**
 * Agent 入口的"用户回合"包装：把文本 + 多模态块以出现顺序一次性提交。
 *
 * 与 ChatMessage.User 不互替：前者是 Agent 层输入视角，后者是 LLM/Memory
 * 层消息视角；通过 ChatMessage.User(query.parts) 互转。
 *
 * @param label 消息来源标签（单值）：随记忆条目持久化，UI 可据此分类渲染；
 *   构建请求时对带 label 的 User 消息拼 `"label: content"` 前缀使模型可见。
 */
@Serializable
public data class AgentQuery(
    public val parts: List<ContentPart>,
    public val label: String? = null,
) {
    init {
        require(parts.isNotEmpty()) { "AgentQuery.parts must not be empty" }
    }

    public companion object {
        /** 纯文本便捷入口。 */
        public fun text(content: String, label: String? = null): AgentQuery =
            AgentQuery(listOf(ContentPart.Text(content)), label = label)
    }
}
