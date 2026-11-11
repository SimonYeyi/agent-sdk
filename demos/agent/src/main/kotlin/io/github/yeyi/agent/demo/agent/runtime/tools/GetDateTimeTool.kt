package io.github.yeyi.agent.demo.agent.runtime.tools

import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.tool.UiTool
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
class GetDateTimeTool : UiTool() {

    override val name: String = "get_datetime"
    override val description: String = "返回当前日期与时间"
    override val toolSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement =
        buildJsonObject {
            put("date", JsonPrimitive(LocalDate.now().toString()))
            put("time", JsonPrimitive(Instant.now().toString()))
        }

    override suspend fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val d = result?.jsonObject ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            root = TreeNode(
                component = "Card",
                bindings = buildJsonObject {
                    put("spacing", JsonPrimitive(12))
                    put("padding", JsonPrimitive(16))
                },
                children = listOf(
                    // 标题：emoji + titleLarge，作为卡片视觉锚点
                    text("🕐 当前时间", "style" to "titleLarge", "fontWeight" to "SemiBold"),
                    TreeNode(component = "Divider", bindings = emptyMap()),
                    // 字段行：label/value 用 SpaceBetween 撑开，label 弱化、value 强调
                    fieldRow("日期", d.str("date")),
                    fieldRow("时间", d.str("time")),
                ),
            ),
        )
    }

    /** 文本节点：带可选样式覆写，缺省走 Text 组件默认形态。 */
    private fun text(content: String, vararg extra: Pair<String, String>): TreeNode {
        val bindings = buildJsonObject {
            put("text", JsonPrimitive(content))
            extra.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        return TreeNode(component = "Text", bindings = bindings)
    }

    /** 字段行：label（弱化）+ value（强调）用 SpaceBetween 撑开整行。 */
    private fun fieldRow(label: String, value: String): TreeNode = TreeNode(
        component = "Row",
        bindings = buildJsonObject {
            put("arrangement", JsonPrimitive("SpaceBetween"))
            put("verticalAlignment", JsonPrimitive("CenterVertically"))
        },
        children = listOf(
            text(label, "style" to "labelMedium", "color" to "onSurfaceVariant"),
            text(value, "style" to "bodyMedium", "fontWeight" to "Medium"),
        ),
    )

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: "-"
}
