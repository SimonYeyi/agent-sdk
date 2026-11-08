package io.github.yeyi.agent.demo.agent.runtime.tools

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
 * 产卡方式 2（完全自定义模板）：buildCard 只产出单模板节点 + 业务数据，
 * 布局、按钮与动作都归模板所有——工具不感知按钮节点，也不声明动作。
 *
 * 模板内嵌「开启导航」动作：点击经 ActionEvent 回流触发新一轮推理。
 * 经纬度等位置数据已随工具回执进入记忆，模型后续轮次据此执行导航；
 * 动作载荷里另有一份同样的绑定数据，随动作回流到应用，由应用决定是否喂给模型。
 */
class GetLocationTool(sink: UiSink) : UiGenTool(sink) {

    override val name: String = "get_location"
    override val description: String = "获取当前位置"
    override val toolSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement =
        buildJsonObject {
            put("city", JsonPrimitive("珠海"))
            put("province", JsonPrimitive("广东"))
            put("country", JsonPrimitive("中国"))
            put("latitude", JsonPrimitive(22.2769))
            put("longitude", JsonPrimitive(113.5678))
        }

    override fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext,
    ): CardIr? {
        val obj = result?.jsonObject ?: return null
        // 模板卡：工具只交 root + 数据，按钮与动作都在模板里
        return CardIr(
            instanceId = context.toolCallId,
            root = TreeNode(
                component = "Location",
                bindings = obj,
            ),
        )
    }
}
