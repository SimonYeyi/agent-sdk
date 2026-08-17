package io.github.yeyi.agent.demo.team.smartCockpit

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.toolset.Toolset
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

// ===== 舒适控制工具集 =====

class AcTool : Tool {
    override val name = "ac_control"
    override val description = "空调控制"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"temperature":{"type":"number"},"fanSpeed":{"type":"number"}},"required":["temperature"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(3000)
        return ToolExecutionResult.success("空调已设置")
    }
}

class SeatTool : Tool {
    override val name = "seat_control"
    override val description = "座椅控制"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"seat":{"type":"string"},"heating":{"type":"number"},"ventilation":{"type":"number"}},"required":["seat"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(2000)
        return ToolExecutionResult.success("座椅控制成功")
    }
}

val comfortControlToolset: Toolset = Toolset("comfort_control", "座舱舒适控制，包含空调、座椅等").apply {
    add(AcTool())
    add(SeatTool())
}

// ===== 车内环境工具集 =====

class WindowTool : Tool {
    override val name = "window_control"
    override val description = "车窗控制"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"position":{"type":"string"},"action":{"type":"string","enum":["open","close"]}},"required":["position","action"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(2000)
        return ToolExecutionResult.success("车窗控制成功")
    }
}

class AmbientLightTool : Tool {
    override val name = "ambient_light_control"
    override val description = "氛围灯控制"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"mode":{"type":"string","enum":["normal","relax","party","sleep"]}},"required":["mode"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(1500)
        return ToolExecutionResult.success("氛围灯已设置")
    }
}

val cabinEnvironmentToolset: Toolset = Toolset("cabin_environment", "车内环境控制，包含车窗、氛围灯等").apply {
    add(WindowTool())
    add(AmbientLightTool())
}

// ===== 驾驶辅助工具集 =====

class NavigateTool : Tool {
    override val name = "navigate"
    override val description = "导航到目的地"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"destination":{"type":"string"},"route":{"type":"string"}},"required":["destination"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(4000)
        return ToolExecutionResult.success("导航已启动")
    }
}

class DashCamTool : Tool {
    override val name = "dash_cam_control"
    override val description = "行车记录仪控制"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"action":{"type":"string","enum":["start","stop","snapshot"]}},"required":["action"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(2000)
        return ToolExecutionResult.success("行车记录仪控制成功")
    }
}

val drivingAssistToolset: Toolset = Toolset("driving_assist", "驾驶辅助，包含导航、行车记录仪等").apply {
    add(NavigateTool())
    add(DashCamTool())
}
