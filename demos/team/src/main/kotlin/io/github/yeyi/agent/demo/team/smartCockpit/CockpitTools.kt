package io.github.yeyi.agent.demo.team.smartCockpit

import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.tool.UiTool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A2UI 标准组件卡片节点助手：team demo 产卡工具统一经此构造 IR 组件树
 * （Card/Text/Row/Column/Divider/Button），组件名与绑定用后端中立的语义词，
 * 渲染后端各自映射自己的组件契约（A2uiRenderAdapter 按官方目录逐组件翻译）。
 */
internal object A2uiNodes {

    /** 文本节点：IR 语义绑定 content（文案）+ style（样式词表，后端映射各自的 variant）。 */
    fun text(content: String, style: String = "body"): TreeNode = TreeNode(
        component = "Text",
        bindings = buildJsonObject {
            put("content", JsonPrimitive(content))
            put("style", JsonPrimitive(style))
        },
    )

    /** 字段行：label + value 两个文本并排（Row children 数组承载）。 */
    fun fieldRow(label: String, value: String): TreeNode = row(text(label, "caption"), text(value))

    /** 横向布局：IR Row。 */
    fun row(vararg children: TreeNode): TreeNode = TreeNode(component = "Row", children = children.toList())

    /** 纵向布局：IR Column。 */
    fun column(vararg children: TreeNode): TreeNode = TreeNode(component = "Column", children = children.toList())

    /** 分隔线：IR Divider。 */
    fun divider(): TreeNode = TreeNode(component = "Divider")

    /**
     * 动作按钮：IR Button + 节点自带动作。label 即按钮文案，也是回流文案
     * （[io.github.yeyi.agent.genui.ir.Action.reflowable] 的组成）；
     * 需要带参数回流时由调用方在 [Action.payload] 给出。
     */
    fun button(label: String, action: Action, variant: String = "secondary"): TreeNode = TreeNode(
        component = "Button",
        bindings = buildJsonObject {
            put("variant", JsonPrimitive(variant))
        },
        action = action,
        children = listOf(text(label)),
    )
}

/** 独立工具：获取时间，产出 A2UI 时间卡。 */
class GetTimeTool : UiTool() {
    override val name = "get_time"
    override val description = "获取当前时间"
    override val toolSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(200)
        return buildJsonObject {
            put("time", JsonPrimitive(java.time.LocalTime.now().withNano(0).toString()))
        }
    }

    override suspend fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val time = result?.jsonObject?.str("time") ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("当前时间", "caption"),
                    A2uiNodes.text(time, "title2"),
                    A2uiNodes.divider(),
                    A2uiNodes.row(A2uiNodes.button("刷新时间", Action("刷新时间"), "primary")),
                ),
            ),
        )
    }
}

/** 独立工具：获取日期，产出 A2UI 日期卡。 */
class GetDateTool : UiTool() {
    override val name = "get_date"
    override val description = "获取当前日期"
    override val toolSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(100)
        return buildJsonObject {
            put("date", JsonPrimitive(java.time.LocalDate.now().toString()))
        }
    }

    override suspend fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val date = result?.jsonObject?.str("date") ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("当前日期", "caption"),
                    A2uiNodes.text(date, "title2"),
                    A2uiNodes.divider(),
                    A2uiNodes.row(A2uiNodes.button("刷新日期", Action("刷新日期"), "primary")),
                ),
            ),
        )
    }
}

/** 独立工具：获取车辆状态，产出 A2UI 车辆状态卡。 */
class GetCarStatusTool : UiTool() {
    override val name = "get_car_status"
    override val description = "获取车辆状态"
    override val toolSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(2500)
        return buildJsonObject {
            put("status", JsonPrimitive("正常"))
            put("battery", JsonPrimitive("85%"))
            put("mileage", JsonPrimitive("12845 km"))
        }
    }

    override suspend fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val obj = result?.jsonObject ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("车辆状态", "title3"),
                    A2uiNodes.divider(),
                    A2uiNodes.fieldRow("状态", obj.str("status")),
                    A2uiNodes.fieldRow("电量", obj.str("battery")),
                    A2uiNodes.fieldRow("里程", obj.str("mileage")),
                    A2uiNodes.divider(),
                    A2uiNodes.row(A2uiNodes.button("刷新状态", Action("刷新状态"), "primary")),
                ),
            ),
        )
    }
}

/** 独立工具：获取能耗信息，产出 A2UI 能耗卡。 */
class GetEnergyTool : UiTool() {
    override val name = "get_energy"
    override val description = "获取能耗信息"
    override val toolSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(2000)
        return buildJsonObject {
            put("consumption", JsonPrimitive("18.5"))
            put("unit", JsonPrimitive("kWh/100km"))
            put("total", JsonPrimitive("12.3 kWh"))
        }
    }

    override suspend fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val obj = result?.jsonObject ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("能耗信息", "title3"),
                    A2uiNodes.divider(),
                    A2uiNodes.fieldRow("百公里电耗", "${obj.str("consumption")} ${obj.str("unit")}"),
                    A2uiNodes.fieldRow("本次行程能耗", obj.str("total")),
                    A2uiNodes.divider(),
                    A2uiNodes.row(A2uiNodes.button("刷新能耗", Action("刷新能耗"), "primary")),
                ),
            ),
        )
    }
}

private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: "-"
