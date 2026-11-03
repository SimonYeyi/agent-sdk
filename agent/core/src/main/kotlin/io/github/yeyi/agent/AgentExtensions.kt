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
 * 终局三选一（与 [Agent.run] 的契约一致）：
 * - [AgentEvent.Final] → 返回其 [AgentEvent.Final.result]
 * - [AgentEvent.Failed] → 抛出其 [AgentEvent.Failed.cause]（原始异常，不重新包装）
 * - [AgentEvent.Canceled] → 抛出其 [AgentEvent.Canceled.cause]（载荷本就是
 *   [kotlinx.coroutines.CancellationException]，抛出不误判失败、不重新包装）——
 *   保持「取消即异常」的返回形态，衔接既有调用方心智。
 *
 * 其他 [AgentEvent] 子类型被忽略。
 * Flow 自身异常按 Flow 协议传播。
 */
public suspend fun Flow<AgentEvent>.awaitResult(): AgentResult {
    val terminated = filter { it.isTerminal }.first()
    return when (terminated) {
        is AgentEvent.Final -> terminated.result
        is AgentEvent.Failed -> throw terminated.cause
        is AgentEvent.Canceled -> throw terminated.cause
        else -> error("unreachable: filter restricts to Final|Failed|Canceled, got ${terminated::class.simpleName}")
    }
}
