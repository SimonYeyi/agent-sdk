package io.github.yeyi.agent.demo.realtime.smartCockpit

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 独立工具：获取时间 */
class GetTimeTool : Tool {
    override val name = "get_time"
    override val description = "获取当前时间"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
        return ToolExecutionResult.success("当前时间: ${java.time.LocalDateTime.now()}")
    }
}

/** 独立工具：获取日期 */
class GetDateTool : Tool {
    override val name = "get_date"
    override val description = "获取当前日期"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
        return ToolExecutionResult.success("当前日期: ${java.time.LocalDate.now()}")
    }
}

/** 独立工具：获取车辆状态 */
class GetCarStatusTool : Tool {
    override val name = "get_car_status"
    override val description = "获取车辆状态"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
        delay(2500)
        return ToolExecutionResult.success("车辆状态: 正常, 电量 85%")
    }
}

/** 独立工具：获取能耗信息 */
class GetEnergyTool : Tool {
    override val name = "get_energy"
    override val description = "获取能耗信息"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
        delay(2000)
        return ToolExecutionResult.success("能耗: 电耗 18.5kWh/100km")
    }
}

/** 独立工具：音乐控制 */
class MusicControlTool : Tool {
    override val name = "control_music"
    override val description =
        "用户要求控制音乐播放时调用。通过 action 参数指定具体操作:play=开始/恢复播放,pause=暂停,next=下一首,previous=上一首。"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"action":{"type":"string","description":"要执行的音乐控制动作","enum":["play","pause","next","previous"]}},"required":["action"]}"""
    )

    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
        val action = (arguments as? JsonObject)
            ?.get("action")
            ?.jsonPrimitive
            ?.content
            ?: return ToolExecutionResult.success("未知动作")
        return ToolExecutionResult.success(
            when (action) {
                "play" -> "已开始播放 陈奕迅"
                "pause" -> "已暂停播放 陈奕迅"
                "next" -> "已切换到下一首 张学友"
                "previous" -> "已切换到上一首 陈奕迅"
                else -> "未知动作: $action"
            }
        )
    }
}
