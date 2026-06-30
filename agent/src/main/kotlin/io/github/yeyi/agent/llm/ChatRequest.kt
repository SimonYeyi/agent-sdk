package io.github.yeyi.agent.llm

/**
 * LLM 聊天请求数据类。
 *
 * @param messages 对话历史列表，首位通常是 [ChatMessage.System]
 * @param tools 可用工具定义列表，为空时不启用工具调用
 * @param temperature 采样温度，控制随机性；null 表示使用 provider 默认值
 * @param maxTokens 最大生成 token 数；null 表示不限制
 * @param stopSequences 遇到此列表中的字符串时停止生成
 */
public data class ChatRequest(
    public val messages: List<ChatMessage>,
    public val tools: List<ToolDefinition> = emptyList(),
    public val temperature: Double? = null,
    public val maxTokens: Int? = null,
    public val stopSequences: List<String> = emptyList()
)
