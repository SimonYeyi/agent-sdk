package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ToolDefinition
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val EMPTY_SCHEMA = Json.parseToJsonElement("""{"type":"object","properties":{}}""")

internal fun ToolRegistry.definitions(): List<ToolDefinition> {
    return all().map { tool ->
        val schema = when (val ps = tool.parametersSchema) {
            is ToolParameters.Empty -> EMPTY_SCHEMA
            is ToolParameters.JsonSchema -> Json.parseToJsonElement(ps.schema)
        }
        ToolDefinition(tool.name, tool.description, schema.jsonObject)
    }
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
