package io.github.yeyi.agent.demo.agent.demo.tools

import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.tool.UiGenTool
import io.github.yeyi.agent.genui.tool.UiSinkRegistry
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
 */
class GetLocationTool(registry: UiSinkRegistry) : UiGenTool(registry) {

    override val name: String = "get_location"
    override val description: String = "获取当前位置"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getData(arguments: JsonElement, context: ToolExecutionContext): JsonElement =
        buildJsonObject {
            put("city", JsonPrimitive("珠海"))
            put("province", JsonPrimitive("广东"))
            put("country", JsonPrimitive("中国"))
            put("latitude", JsonPrimitive(22.2769))
            put("longitude", JsonPrimitive(113.5678))
        }

    override fun buildCard(
        data: JsonElement,
        arguments: JsonElement,
        context: ToolExecutionContext,
    ): CardIr = CardIr(
        instanceId = context.toolCallId,
        root = TreeNode(component = "Location", bindings = data.jsonObject),
    )
}
