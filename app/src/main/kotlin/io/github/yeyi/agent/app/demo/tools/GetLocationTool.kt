package io.github.yeyi.agent.app.demo.tools

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 获取当前位置（固定返回珠海）
 */
class GetLocationTool : Tool {
    override val name: String = "get_location"
    override val description: String = "获取当前位置"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        // 固定返回珠海坐标
        val location = buildJsonObject {
            put("city", JsonPrimitive("珠海"))
            put("province", JsonPrimitive("广东"))
            put("country", JsonPrimitive("中国"))
            put("latitude", JsonPrimitive(22.2769))
            put("longitude", JsonPrimitive(113.5678))
        }
        return ToolExecutionResult(content = location.toString())
    }
}
