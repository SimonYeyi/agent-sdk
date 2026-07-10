package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ToolDefinition
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val EMPTY_SCHEMA = Json.parseToJsonElement("""{"type":"object","properties":{}}""")

/**
 * 将当前 [Tool] 转换为 [ToolDefinition]。
 *
 * [Tool.parametersSchema] 为 [ToolParameters.Empty] 时返回空对象 schema，
 * 为 [ToolParameters.JsonSchema] 时解析为 [kotlinx.serialization.json.JsonObject]。
 *
 * @return 可用于 LLM schema 渲染的结构化工具定义。
 */
public fun Tool.toDefinition(): ToolDefinition {
    val schema = when (val ps = parametersSchema) {
        is ToolParameters.Empty -> EMPTY_SCHEMA
        is ToolParameters.JsonSchema -> Json.parseToJsonElement(ps.schema)
    }
    return ToolDefinition(name, description, schema.jsonObject)
}

/**
 * 等待并返回 agent 运行的最终结果。
 *
 * 终端事件识别:
 * - [AgentEvent.Final] → 返回其 [AgentEvent.Final.result]
 * - [AgentEvent.Failed] → 抛出其 [AgentEvent.Failed.cause](一定是 [AgentException])
 *
 * 其他 [AgentEvent] 子类型被忽略。
 * Flow 自身异常按 Flow 协议传播。
 */
public suspend fun Flow<AgentEvent>.awaitResult(): AgentResult {
    val terminal = filter { it is AgentEvent.Final || it is AgentEvent.Failed }.first()
    return when (terminal) {
        is AgentEvent.Final -> terminal.result
        is AgentEvent.Failed -> throw terminal.cause
        else -> error("unreachable: filter restricts to Final|Failed, got ${terminal::class.simpleName}")
    }
}
