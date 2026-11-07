package io.github.yeyi.agent.demo.agent.runtime.tools

import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.tool.UiGenTool
import io.github.yeyi.agent.genui.tool.UiSink
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 获取当前位置(固定返回珠海),产出一张定位卡片。
 *
 * 卡片经 UiSink 旁路广播,永不写入记忆;记忆里只留结构化回执文本。
 *
 * 产卡方式 2（完全自定义模板）：buildCard 只产出单模板节点，
 * 渲染由 demo 模板 LocationCard 完成——布局与文本全原生 Compose，不依赖基础组件。
 *
 * 卡片内嵌「开启导航」动作按钮：点击经 ActionEvent 回流触发新一轮推理，
 * 经纬度等位置数据已随本次 getResult 写入记忆，模型后续轮次据此执行导航。
 */
class GetLocationTool(sink: UiSink) : UiGenTool(sink) {

    override val name: String = "get_location"
    override val description: String = "获取当前位置"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement =
        buildJsonObject {
            put("city", JsonPrimitive("珠海"))
            put("province", JsonPrimitive("广东"))
            put("country", JsonPrimitive("中国"))
            put("latitude", JsonPrimitive(22.2769))
            put("longitude", JsonPrimitive(113.5678))
        }

    // 信息卡：卡片展示查询结果，本轮不终结，模型看完回执自行决定继续还是收尾
    override fun cardKind(arguments: JsonElement): CardKind = CardKind.INFO

    override fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val obj = result?.jsonObject ?: return null
        return CardIr(
            instanceId = context.toolCallId,
            actions = listOf(
                Action("navigate", "开启导航"),
                // 动作按钮是 root 的子节点（Button IR），引擎递归渲染并各自绑定白名单动作，
                // 多按钮天然支持——模板只决定布局位置，不感知按钮实现。
                // 未来扩展按钮：actions 声明 + children 追加一个 Button 节点即可。
            ),
            root = TreeNode(
                component = "Location",
                bindings = obj,
                children = listOf(
                    TreeNode(
                        component = "Button",
                        // variant 由引擎 Button 组件消费；label 自动取 action.label
                        bindings = buildJsonObject { put("variant", JsonPrimitive("primary")) },
                        action = "navigate",
                    ),
                ),
            ),
        )
    }
}
