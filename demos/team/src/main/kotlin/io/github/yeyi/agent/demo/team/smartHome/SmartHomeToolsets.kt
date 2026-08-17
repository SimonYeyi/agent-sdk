package io.github.yeyi.agent.demo.team.smartHome

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.toolset.Toolset
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

// ===== 家居控制工具集 =====

class LightTool : Tool {
    override val name = "light_control"
    override val description = "控制灯光（开关、亮度）"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"room":{"type":"string"},"action":{"type":"string","enum":["on","off","dim"]}},"required":["room","action"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(2000)
        return ToolExecutionResult.success("灯光控制成功")
    }
}

class AcTool : Tool {
    override val name = "ac_control"
    override val description = "控制空调温度"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"room":{"type":"string"},"temperature":{"type":"number"}},"required":["room","temperature"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(3000)
        return ToolExecutionResult.success("空调已设置")
    }
}

class CurtainTool : Tool {
    override val name = "curtain_control"
    override val description = "控制窗帘开关"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"room":{"type":"string"},"action":{"type":"string","enum":["open","close"]}},"required":["room","action"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(2000)
        return ToolExecutionResult.success("窗帘控制成功")
    }
}

val homeControlToolset: Toolset = Toolset("home_control", "家居控制，包含灯光、空调、窗帘等").apply {
    add(LightTool())
    add(AcTool())
    add(CurtainTool())
}

// ===== 家电控制工具集 =====

class WaterHeaterTool : Tool {
    override val name = "water_heater_control"
    override val description = "控制热水器"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"room":{"type":"string"},"action":{"type":"string","enum":["on","off"]},"temperature":{"type":"number"}},"required":["room","action"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(2500)
        return ToolExecutionResult.success("热水器控制成功")
    }
}

class RobotCleanerTool : Tool {
    override val name = "robot_cleaner_control"
    override val description = "控制扫地机器人"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"room":{"type":"string"},"action":{"type":"string","enum":["start","stop","return"]}},"required":["room","action"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(3000)
        return ToolExecutionResult.success("扫地机器人控制成功")
    }
}

val applianceControlToolset: Toolset = Toolset("appliance_control", "家电控制，包含热水器、扫地机器人等").apply {
    add(WaterHeaterTool())
    add(RobotCleanerTool())
}

// ===== 安防控制工具集 =====

class DoorLockTool : Tool {
    override val name = "door_lock_control"
    override val description = "控制门锁"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"door":{"type":"string"},"action":{"type":"string","enum":["lock","unlock"]}},"required":["door","action"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(1500)
        return ToolExecutionResult.success("门锁控制成功")
    }
}

class CameraTool : Tool {
    override val name = "camera_control"
    override val description = "摄像头控制"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"camera":{"type":"string"},"action":{"type":"string","enum":["start","stop","snapshot"]}},"required":["camera","action"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(2000)
        return ToolExecutionResult.success("摄像头控制成功")
    }
}

val securityControlToolset: Toolset = Toolset("security_control", "安防控制，包含门锁、摄像头等").apply {
    add(DoorLockTool())
    add(CameraTool())
}
