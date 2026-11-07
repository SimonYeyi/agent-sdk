package io.github.yeyi.agent.demo.agent.runtime.tools

import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.tool.UiGenTool
import io.github.yeyi.agent.genui.tool.UiSinkRegistry
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 问询卡示例：当用户想查天气却没说城市时，产出一张问询卡请用户点选城市。
 *
 * 问询卡是「卡片即问询」：本轮到此为止（[cardKind] 为 [CardKind.ASK]，成功即终结），
 * 模型不再追问，用户在卡片上点选城市——点击经 [io.github.yeyi.agent.genui.ir.ActionEvent]
 * 回流，动作文案作为新一轮的用户输入，模型据此调用 get_weather。
 *
 * 问询卡没有业务数据可回执（getResult 不复写），回执为「Waiting for user reply」；
 * 卡片必须产出——问询卡未产出时基类判失败，不会让回合空转终结。
 */
class ChooseCityTool(registry: UiSinkRegistry) : UiGenTool(registry) {

    override val name: String = "choose_city"
    override val description: String = "当需要跟用户确定城市时使用"
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    // 问询卡：卡片即问询，本轮终结等用户点选
    override fun cardKind(arguments: JsonElement): CardKind = CardKind.ASK

    override fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext
    ): CardIr {
        return CardIr(
            instanceId = context.toolCallId,
            actions = CITIES.map { Action("city_$it", "查询${it}的天气") },
            root = TreeNode(
                component = "Card",
                children = listOf(
                    textNode("你想查询哪个城市的天气？"),
                    TreeNode(component = "Divider"),
                    TreeNode(
                        component = "Column",
                        bindings = buildJsonObject { put("spacing", JsonPrimitive(8)) },
                        children = CITIES.map { city ->
                            TreeNode(
                                component = "Button",
                                // label 自动取 action.label；city 绑定进 payload，回流时随动作带出
                                bindings = buildJsonObject {
                                    put("variant", JsonPrimitive("bordered"))
                                    put("city", JsonPrimitive(city))
                                },
                                action = "city_$city",
                            )
                        },
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

    private companion object {
        val CITIES = listOf("北京", "上海", "广州", "深圳")
    }
}
