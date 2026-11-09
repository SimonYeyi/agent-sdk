package io.github.yeyi.agent.demo.team.smartCockpit

import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.tool.UiGenTool
import io.github.yeyi.agent.genui.tool.UiSinkRegistry
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.toolset.Toolset
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ===== 舒适控制工具集 =====

class AcTool(registry: UiSinkRegistry) : UiGenTool(registry) {
    override val name = "ac_control"
    override val description = "空调控制"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"temperature":{"type":"number"},"fanSpeed":{"type":"number"}},"required":["temperature"]}"""
    )
    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(3000)
        val obj = arguments.jsonObject
        return buildJsonObject {
            put("temperature", JsonPrimitive(obj["temperature"]?.jsonPrimitive?.contentOrNull ?: "24"))
            put("fanSpeed", JsonPrimitive(obj["fanSpeed"]?.jsonPrimitive?.contentOrNull ?: "1"))
        }
    }

    override fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val obj = result?.jsonObject ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            actions = listOf(
                Action("temp_up", "空调温度升高一度"),
                Action("temp_down", "空调温度降低一度"),
            ),
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("空调控制", "h3"),
                    A2uiNodes.divider(),
                    A2uiNodes.fieldRow("设定温度", "${obj.str("temperature")}°C"),
                    A2uiNodes.fieldRow("风速档位", obj.str("fanSpeed")),
                    A2uiNodes.divider(),
                    A2uiNodes.row(
                        A2uiNodes.button("温度升高", "temp_up", "primary", "temperature" to obj.str("temperature")),
                        A2uiNodes.button("温度降低", "temp_down", "default", "temperature" to obj.str("temperature")),
                    ),
                ),
            ),
        )
    }
}

class SeatTool(registry: UiSinkRegistry) : UiGenTool(registry) {
    override val name = "seat_control"
    override val description = "座椅控制"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"seat":{"type":"string"},"heating":{"type":"number"},"ventilation":{"type":"number"}},"required":["seat"]}"""
    )
    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(2000)
        val obj = arguments.jsonObject
        return buildJsonObject {
            put("seat", JsonPrimitive(obj["seat"]?.jsonPrimitive?.contentOrNull ?: "主驾"))
            put("heating", JsonPrimitive(obj["heating"]?.jsonPrimitive?.contentOrNull ?: "0"))
            put("ventilation", JsonPrimitive(obj["ventilation"]?.jsonPrimitive?.contentOrNull ?: "0"))
        }
    }

    override fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val obj = result?.jsonObject ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            actions = listOf(
                Action("heat", "开启座椅加热"),
                Action("vent", "开启座椅通风"),
            ),
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("座椅控制", "h3"),
                    A2uiNodes.divider(),
                    A2uiNodes.fieldRow("座椅", obj.str("seat")),
                    A2uiNodes.fieldRow("加热档位", obj.str("heating")),
                    A2uiNodes.fieldRow("通风档位", obj.str("ventilation")),
                    A2uiNodes.divider(),
                    A2uiNodes.row(
                        A2uiNodes.button("开启加热", "heat", "primary", "seat" to obj.str("seat")),
                        A2uiNodes.button("开启通风", "vent", "default", "seat" to obj.str("seat")),
                    ),
                ),
            ),
        )
    }
}

fun comfortControlToolset(registry: UiSinkRegistry): Toolset = Toolset("comfort_control", "座舱舒适控制，包含空调、座椅等").apply {
    add(AcTool(registry))
    add(SeatTool(registry))
}

// ===== 车内环境工具集 =====

class WindowTool(registry: UiSinkRegistry) : UiGenTool(registry) {
    override val name = "window_control"
    override val description = "车窗控制"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"position":{"type":"string"},"action":{"type":"string","enum":["open","close"]}},"required":["position","action"]}"""
    )
    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(2000)
        val obj = arguments.jsonObject
        val position = obj["position"]?.jsonPrimitive?.contentOrNull ?: "主驾"
        val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: "open"
        return buildJsonObject {
            put("position", JsonPrimitive(position))
            put("state", JsonPrimitive(if (action == "open") "已打开" else "已关闭"))
        }
    }

    override fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val obj = result?.jsonObject ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            actions = listOf(
                Action("open", "打开车窗"),
                Action("close", "关闭车窗"),
            ),
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("车窗控制", "h3"),
                    A2uiNodes.divider(),
                    A2uiNodes.fieldRow("车窗", obj.str("position")),
                    A2uiNodes.fieldRow("状态", obj.str("state")),
                    A2uiNodes.divider(),
                    A2uiNodes.row(
                        A2uiNodes.button("打开车窗", "open", "primary", "position" to obj.str("position")),
                        A2uiNodes.button("关闭车窗", "close", "default", "position" to obj.str("position")),
                    ),
                ),
            ),
        )
    }
}

class AmbientLightTool(registry: UiSinkRegistry) : UiGenTool(registry) {
    override val name = "ambient_light_control"
    override val description = "氛围灯控制"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"mode":{"type":"string","enum":["normal","relax","party","sleep"]}},"required":["mode"]}"""
    )
    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(1500)
        val mode = arguments.jsonObject["mode"]?.jsonPrimitive?.contentOrNull ?: "normal"
        return buildJsonObject {
            put("mode", JsonPrimitive(mode))
            put("modeLabel", JsonPrimitive(MODE_LABELS[mode] ?: mode))
        }
    }

    override fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val obj = result?.jsonObject ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            actions = listOf(
                Action("relax", "切换氛围灯为放松模式"),
                Action("party", "切换氛围灯为派对模式"),
                Action("sleep", "切换氛围灯为睡眠模式"),
            ),
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("氛围灯控制", "h3"),
                    A2uiNodes.divider(),
                    A2uiNodes.fieldRow("当前模式", obj.str("modeLabel")),
                    A2uiNodes.divider(),
                    A2uiNodes.row(
                        A2uiNodes.button("放松", "relax", "primary"),
                        A2uiNodes.button("派对", "party", "default"),
                        A2uiNodes.button("睡眠", "sleep", "default"),
                    ),
                ),
            ),
        )
    }

    private companion object {
        val MODE_LABELS = mapOf(
            "normal" to "标准",
            "relax" to "放松",
            "party" to "派对",
            "sleep" to "睡眠",
        )
    }
}

fun cabinEnvironmentToolset(registry: UiSinkRegistry): Toolset = Toolset("cabin_environment", "车内环境控制，包含车窗、氛围灯等").apply {
    add(WindowTool(registry))
    add(AmbientLightTool(registry))
}

// ===== 驾驶辅助工具集 =====

class NavigateTool(registry: UiSinkRegistry) : UiGenTool(registry) {
    override val name = "navigate"
    override val description = "导航到目的地"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"destination":{"type":"string"},"route":{"type":"string"}},"required":["destination"]}"""
    )
    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(4000)
        val obj = arguments.jsonObject
        return buildJsonObject {
            put("destination", JsonPrimitive(obj["destination"]?.jsonPrimitive?.contentOrNull ?: "未知"))
            put("route", JsonPrimitive(obj["route"]?.jsonPrimitive?.contentOrNull ?: "推荐路线"))
        }
    }

    override fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val obj = result?.jsonObject ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            actions = listOf(
                Action("start", "开始导航"),
                Action("replan", "重新规划路线"),
            ),
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("导航", "h3"),
                    A2uiNodes.divider(),
                    A2uiNodes.fieldRow("目的地", obj.str("destination")),
                    A2uiNodes.fieldRow("路线", obj.str("route")),
                    A2uiNodes.divider(),
                    A2uiNodes.row(
                        A2uiNodes.button("开始导航", "start", "primary", "destination" to obj.str("destination")),
                        A2uiNodes.button("重新规划", "replan", "default", "destination" to obj.str("destination")),
                    ),
                ),
            ),
        )
    }
}

class DashCamTool(registry: UiSinkRegistry) : UiGenTool(registry) {
    override val name = "dash_cam_control"
    override val description = "行车记录仪控制"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"action":{"type":"string","enum":["start","stop","snapshot"]}},"required":["action"]}"""
    )
    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(2000)
        val action = arguments.jsonObject["action"]?.jsonPrimitive?.contentOrNull ?: "start"
        return buildJsonObject {
            put("state", JsonPrimitive(if (action == "stop") "已停止录制" else "录制中"))
        }
    }

    override fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val obj = result?.jsonObject ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            actions = listOf(
                Action("start", "开始录制"),
                Action("stop", "停止录制"),
                Action("snapshot", "抓拍一张照片"),
            ),
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("行车记录仪", "h3"),
                    A2uiNodes.divider(),
                    A2uiNodes.fieldRow("状态", obj.str("state")),
                    A2uiNodes.divider(),
                    A2uiNodes.row(
                        A2uiNodes.button("开始录制", "start", "primary"),
                        A2uiNodes.button("停止录制", "stop", "default"),
                        A2uiNodes.button("抓拍", "snapshot", "default"),
                    ),
                ),
            ),
        )
    }
}

fun drivingAssistToolset(registry: UiSinkRegistry): Toolset = Toolset("driving_assist", "驾驶辅助，包含导航、行车记录仪等").apply {
    add(NavigateTool(registry))
    add(DashCamTool(registry))
}

private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: "-"
