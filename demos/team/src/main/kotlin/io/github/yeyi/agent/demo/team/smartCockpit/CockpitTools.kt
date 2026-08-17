package io.github.yeyi.agent.demo.team.smartCockpit

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
        return ToolExecutionResult.success("当前时间: ${java.time.LocalDateTime.now()}")
    }
}

/** 独立工具：获取日期 */
class GetDateTool : Tool {
    override val name = "get_date"
    override val description = "获取当前日期"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(100)
        return ToolExecutionResult.success("当前日期: ${java.time.LocalDate.now()}")
    }
}

/** 独立工具：获取车辆状态 */
class GetCarStatusTool : Tool {
    override val name = "get_car_status"
    override val description = "获取车辆状态"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(2500)
        return ToolExecutionResult.success("车辆状态: 正常, 电量 85%")
    }
}

/** 独立工具：获取能耗信息 */
class GetEnergyTool : Tool {
    override val name = "get_energy"
    override val description = "获取能耗信息"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(2000)
        return ToolExecutionResult.success("能耗: 电耗 18.5kWh/100km")
    }
}
