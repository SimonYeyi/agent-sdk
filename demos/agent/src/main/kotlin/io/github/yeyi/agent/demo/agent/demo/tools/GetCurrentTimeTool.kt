package io.github.yeyi.agent.demo.agent.demo.tools

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * 教学 Tool: 零参数,无错误路径,演示 Tool 接口最简形式。
 * 纯 Kotlin,仅依赖 java.time。
 */
class GetCurrentTimeTool : Tool {
    override val name = "get_current_time"
    override val description = "返回当前 UTC 时间(ISO-8601 格式)"

    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        val now = Instant.now().toString()
        return ToolExecutionResult.success("Current UTC time: $now")
    }
}
