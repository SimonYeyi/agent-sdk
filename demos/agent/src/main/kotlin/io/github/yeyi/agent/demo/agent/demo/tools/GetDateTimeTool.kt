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
import java.time.Instant
import java.time.LocalDate

/**
 * 返回当前日期与时间，产出一张时间卡片。
 *
 * 卡片经 UiSink 旁路广播,永不写入记忆;记忆里只留结构化回执文本。
 */
class GetDateTimeTool(registry: UiSinkRegistry) : UiGenTool(registry) {

    override val name: String = "get_datetime"
    override val description: String = "返回当前日期与时间"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun getData(arguments: JsonElement, context: ToolExecutionContext): JsonElement =
        buildJsonObject {
            put("date", JsonPrimitive(LocalDate.now().toString()))
            put("time", JsonPrimitive(Instant.now().toString()))
        }

    override fun buildCard(
        data: JsonElement,
        arguments: JsonElement,
        context: ToolExecutionContext,
    ): CardIr = CardIr(
        instanceId = context.toolCallId,
        root = TreeNode(component = "DateTime", bindings = data.jsonObject),
    )
}
