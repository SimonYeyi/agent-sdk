package io.github.yeyi.agent.demo.team.smartHome

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

/** 独立工具：获取时间 */
class GetTimeTool : Tool {
    override val name = "get_time"
    override val description = "获取当前时间"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(200)
        return ToolExecutionResult(content = "当前时间: ${java.time.LocalDateTime.now()}")
    }
}

/** 独立工具：获取日期 */
class GetDateTool : Tool {
    override val name = "get_date"
    override val description = "获取当前日期"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(100)
        return ToolExecutionResult(content = "当前日期: ${java.time.LocalDate.now()}")
    }
}

/** 独立工具：获取天气 */
class GetWeatherTool : Tool {
    override val name = "get_weather"
    override val description = "获取天气信息"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
    )

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(3000)
        return ToolExecutionResult(content = "天气: 晴, 26°C")
    }
}

/** 独立工具：获取室内温度 */
class GetIndoorTempTool : Tool {
    override val name = "get_indoor_temp"
    override val description = "获取室内温度"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(500)
        return ToolExecutionResult(content = "室内温度: 24°C")
    }
}
