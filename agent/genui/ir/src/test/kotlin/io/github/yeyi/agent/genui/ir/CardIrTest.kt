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
                        action = Action(id = "refresh", label = "刷新"),
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
    fun `ActionEvent 默认 payload 序列化`() {
        val event = ActionEvent(instanceId = "call_1", action = Action(id = "refresh", label = "刷新"))

        val json = Json.encodeToString(event)
        assertEquals(event, Json.decodeFromString<ActionEvent>(json))
    }
}
