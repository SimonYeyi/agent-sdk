package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class CardIrTest {

    @Test
    fun `CardIr 序列化往返保持完整`() {
        val card = CardIr(
            instanceId = "call_1",
            root = TreeNode(
                component = "WeatherCard",
                bindings = mapOf(
                    "city" to JsonPrimitive("北京"),
                    "summary" to JsonPrimitive("晴天 26°C"),
                ),
                children = listOf(
                    TreeNode(component = "Text", bindings = mapOf("content" to JsonPrimitive("明天有雨"))),
                    TreeNode(
                        component = "Button",
                        action = Action("刷新"),
                    ),
                ),
            ),
        )

        val json = Json.encodeToString(card)
        assertEquals(card, Json.decodeFromString<CardIr>(json))
    }

    @Test
    fun `TreeNode 默认字段序列化`() {
        val node = TreeNode(component = "Text")

        val json = Json.encodeToString(node)
        assertEquals(node, Json.decodeFromString<TreeNode>(json))
    }

    @Test
    fun `ActionEvent 序列化往返保持完整`() {
        val event = ActionEvent(instanceId = "call_1", action = Action("刷新", payload = "北京"))

        val json = Json.encodeToString(event)
        assertEquals(event, Json.decodeFromString<ActionEvent>(json))
    }

    @Test
    fun `回流文案 动作带参数时为 label 加参数`() {
        assertEquals("查看明天天气（北京）", Action("查看明天天气", payload = "北京").reflowText)
    }

    @Test
    fun `回流文案不进协议 序列化只有文案与参数`() {
        val json = Json.encodeToString(Action("查看明天天气", payload = "北京"))

        assertEquals("""{"label":"查看明天天气","payload":"北京"}""", json)
    }

    @Test
    fun `回流文案 动作无参数时只有 label`() {
        assertEquals("查看明天天气", Action("查看明天天气").reflowText)
    }
}
