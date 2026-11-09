package io.github.yeyi.agent.demo.agent.runtime.tools

import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.tool.UiGenTool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate

/**
 * 返回当前日期与时间，产出一张时间卡片。
 *
 * 卡片内嵌工具结果 metadata，随事件流实时送达渲染、随记忆链路持久化恢复。
 *
 * 产卡方式 1（标准 IR）：buildCard 直接产出完整组件数据树，
 * 渲染由 DefaultRenderAdapter 递归查表完成，工具不感知任何渲染细节。
 * IR 只携带语义数据（content），视觉效果由基础组件默认形态决定。
 */
class GetDateTimeTool : UiGenTool() {

    override val name: String = "get_datetime"
    override val description: String = "返回当前日期与时间"
    override val toolSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement =
        buildJsonObject {
            put("date", JsonPrimitive(LocalDate.now().toString()))
            put("time", JsonPrimitive(Instant.now().toString()))
        }

    override fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val d = result?.jsonObject ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            root = TreeNode(
                component = "Card",
                children = listOf(
                    textNode("🕐 当前时间"),
                    TreeNode(component = "Divider"),
                    TreeNode(
                        component = "Column",
                        children = listOf(
                            fieldRow("日期", d.str("date")),
                            fieldRow("时间", d.str("time")),
                        ),
                    ),
                ),
            ),
        )
    }

    /** 文本节点：IR 只带语义数据，视觉由 Text 组件默认形态决定。 */
    private fun textNode(content: String): TreeNode = TreeNode(
        component = "Text",
        bindings = buildJsonObject { put("content", JsonPrimitive(content)) },
    )

    /** 字段行：label + value 两个文本并排。 */
    private fun fieldRow(label: String, value: String): TreeNode = TreeNode(
        component = "Row",
        children = listOf(textNode(label), textNode(value)),
    )

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: "-"
}
