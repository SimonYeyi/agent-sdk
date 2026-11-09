package io.github.yeyi.agent.demo.team.smartCockpit

import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.tool.UiGenTool
import io.github.yeyi.agent.genui.tool.UiSinkRegistry
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
 * A2UI 标准组件卡片节点助手：team demo 产卡工具统一经此构造 A2UI v0.9 basic catalog
 * schema 合规的组件树（Card/Text/Row/Column/Divider/Button），避免各工具重复拼 JSON 绑定。
 *
 * 组件属性严格对齐官方 basic catalog（Text 必填 text、Row/Column 必填 children、
 * Button 必填 child+action、variant 取枚举），保证投影不触发引擎 schema 校验错误。
 */
internal object A2uiNodes {

    /** 文本节点：A2UI Text 必填 text；variant 取 basic catalog 枚举（h1-h5/caption/body）。 */
    fun text(content: String, variant: String = "body"): TreeNode = TreeNode(
        component = "Text",
        bindings = buildJsonObject {
            put("text", JsonPrimitive(content))
            put("variant", JsonPrimitive(variant))
        },
    )

    /** 字段行：label + value 两个文本并排（Row children 数组承载）。 */
    fun fieldRow(label: String, value: String): TreeNode = row(text(label, "caption"), text(value))

    /** 横向布局：A2UI Row 必填 children。 */
    fun row(vararg children: TreeNode): TreeNode = TreeNode(component = "Row", children = children.toList())

    /** 纵向布局：A2UI Column 必填 children。 */
    fun column(vararg children: TreeNode): TreeNode = TreeNode(component = "Column", children = children.toList())

    /** 分隔线：A2UI Divider 无必填属性。 */
    fun divider(): TreeNode = TreeNode(component = "Divider")

    /**
     * 动作按钮：A2UI Button 必填 child + action，child 固定为 Text 标签节点；
     * 点击命中白名单动作后回流，extra 绑定作为回流 [io.github.yeyi.agent.genui.ir.ActionEvent.payload]。
     */
    fun button(label: String, actionId: String, variant: String = "default", vararg extras: Pair<String, String>): TreeNode = TreeNode(
        component = "Button",
        bindings = buildJsonObject {
            put("variant", JsonPrimitive(variant))
            extras.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        },
        action = actionId,
        children = listOf(text(label)),
    )
}

/** 独立工具：获取时间，产出 A2UI 时间卡。 */
class GetTimeTool(registry: UiSinkRegistry) : UiGenTool(registry) {
    override val name = "get_time"
    override val description = "获取当前时间"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(200)
        return buildJsonObject {
            put("time", JsonPrimitive(java.time.LocalTime.now().withNano(0).toString()))
        }
    }

    override fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val time = result?.jsonObject?.str("time") ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            actions = listOf(Action("refresh", "刷新时间")),
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("当前时间", "caption"),
                    A2uiNodes.text(time, "h2"),
                    A2uiNodes.divider(),
                    A2uiNodes.row(A2uiNodes.button("刷新时间", "refresh", "primary")),
                ),
            ),
        )
    }
}

/** 独立工具：获取日期，产出 A2UI 日期卡。 */
class GetDateTool(registry: UiSinkRegistry) : UiGenTool(registry) {
    override val name = "get_date"
    override val description = "获取当前日期"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(100)
        return buildJsonObject {
            put("date", JsonPrimitive(java.time.LocalDate.now().toString()))
        }
    }

    override fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val date = result?.jsonObject?.str("date") ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            actions = listOf(Action("refresh", "刷新日期")),
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("当前日期", "caption"),
                    A2uiNodes.text(date, "h2"),
                    A2uiNodes.divider(),
                    A2uiNodes.row(A2uiNodes.button("刷新日期", "refresh", "primary")),
                ),
            ),
        )
    }
}

/** 独立工具：获取车辆状态，产出 A2UI 车辆状态卡。 */
class GetCarStatusTool(registry: UiSinkRegistry) : UiGenTool(registry) {
    override val name = "get_car_status"
    override val description = "获取车辆状态"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(2500)
        return buildJsonObject {
            put("status", JsonPrimitive("正常"))
            put("battery", JsonPrimitive("85%"))
            put("mileage", JsonPrimitive("12845 km"))
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
            actions = listOf(Action("refresh", "刷新车辆状态")),
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("车辆状态", "h3"),
                    A2uiNodes.divider(),
                    A2uiNodes.fieldRow("状态", obj.str("status")),
                    A2uiNodes.fieldRow("电量", obj.str("battery")),
                    A2uiNodes.fieldRow("里程", obj.str("mileage")),
                    A2uiNodes.divider(),
                    A2uiNodes.row(A2uiNodes.button("刷新状态", "refresh", "primary")),
                ),
            ),
        )
    }
}

/** 独立工具：获取能耗信息，产出 A2UI 能耗卡。 */
class GetEnergyTool(registry: UiSinkRegistry) : UiGenTool(registry) {
    override val name = "get_energy"
    override val description = "获取能耗信息"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        delay(2000)
        return buildJsonObject {
            put("consumption", JsonPrimitive("18.5"))
            put("unit", JsonPrimitive("kWh/100km"))
            put("total", JsonPrimitive("12.3 kWh"))
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
            actions = listOf(Action("refresh", "刷新能耗信息")),
            root = TreeNode(
                component = "Card",
                children = listOf(
                    A2uiNodes.text("能耗信息", "h3"),
                    A2uiNodes.divider(),
                    A2uiNodes.fieldRow("百公里电耗", "${obj.str("consumption")} ${obj.str("unit")}"),
                    A2uiNodes.fieldRow("本次行程能耗", obj.str("total")),
                    A2uiNodes.divider(),
                    A2uiNodes.row(A2uiNodes.button("刷新能耗", "refresh", "primary")),
                ),
            ),
        )
    }
}

private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: "-"
