package io.github.yeyi.agent.llm

import kotlinx.serialization.json.JsonObject

/**
 * 工具定义（用于告诉 LLM 有哪些工具可用）。
 *
 * 此类是 SDK → LLM 的输出数据结构，不参与实际执行。
 * 由 [io.github.yeyi.agent.tool.ToolRegistry.definitions] 生成。
 *
 * @param name 工具名称，对应 [io.github.yeyi.agent.tool.Tool.name]
 * @param description 工具描述，供 LLM 理解用途
 * @param parametersSchema 参数 JSON Schema，LLM 据此生成调用参数
 */
public data class ToolDefinition(
    public val name: String,
    public val description: String,
    public val parametersSchema: JsonObject
)
