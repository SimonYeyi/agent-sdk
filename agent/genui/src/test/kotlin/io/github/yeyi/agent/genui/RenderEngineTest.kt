package io.github.yeyi.agent.genui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderEngineTest {

    @Test
    fun `注册组件正常渲染并携带绑定数据`() {
        val engine = RenderEngine()
        var seen: TreeNode? = null
        engine.register("Text") { node, _, _ -> seen = node }

        val card = CardIr(
            instanceId = "c1",
            root = TreeNode(component = "Text", bindings = mapOf("content" to JsonPrimitive("hi"))),
        )
        engine.render(card) {}

        assertEquals("hi", seen?.bindings?.get("content")?.jsonPrimitive?.content)
    }

    @Test
    fun `未知组件降级为 fallback 不中断渲染`() {
        val engine = RenderEngine()
        var fallbackSeen = false
        engine.registerFallback { _, _, _ -> fallbackSeen = true }

        val card = CardIr(instanceId = "c1", root = TreeNode(component = "Unknown"))
        engine.render(card) {}

        assertTrue(fallbackSeen)
    }

    @Test
    fun `树遍历先序 子节点也被渲染`() {
        val engine = RenderEngine()
        val order = mutableListOf<String>()
        engine.register("Row") { _, _, _ -> order += "Row" }
        engine.register("Text") { _, _, _ -> order += "Text" }

        val card = CardIr(
            instanceId = "c1",
            root = TreeNode(
                component = "Row",
                children = listOf(
                    TreeNode(component = "Text"),
                    TreeNode(component = "Text", children = listOf(TreeNode(component = "Text"))),
                ),
            ),
        )
        engine.render(card) {}

        assertEquals(listOf("Row", "Text", "Text", "Text"), order)
    }

    @Test
    fun `白名单动作节点触发回流 引擎构造 UiActionEvent`() {
        val engine = RenderEngine()
        val events = mutableListOf<UiActionEvent>()
        engine.register("Button") { _, _, emit -> emit() }

        val card = CardIr(
            instanceId = "c1",
            root = TreeNode(
                component = "Button",
                bindings = mapOf("routeId" to JsonPrimitive("r1")),
                action = "choose",
            ),
            actions = listOf(Action(id = "choose", label = "选择")),
        )
        engine.render(card) { events += it }

        assertEquals(
            listOf(UiActionEvent("c1", "choose", JsonObject(mapOf("routeId" to JsonPrimitive("r1"))))),
            events,
        )
    }

    @Test
    fun `节点动作未命中白名单 渲染为静态 调用 emit 也不产生事件`() {
        val engine = RenderEngine()
        var actionSeen: Action? = Action(id = "x", label = "x")
        engine.register("Button") { _, action, emit ->
            actionSeen = action
            emit()
        }

        val card = CardIr(
            instanceId = "c1",
            root = TreeNode(component = "Button", action = "hack"),
            actions = listOf(Action(id = "refresh", label = "刷新")),
        )
        val events = mutableListOf<UiActionEvent>()
        engine.render(card) { events += it }

        assertNull(actionSeen)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `白名单动作 id 重复 全卡降级纯展示`() {
        val engine = RenderEngine()
        var actionSeen: Action? = Action(id = "x", label = "x")
        engine.register("Button") { _, action, _ -> actionSeen = action }

        val card = CardIr(
            instanceId = "c1",
            root = TreeNode(component = "Button", action = "choose"),
            actions = listOf(
                Action(id = "choose", label = "选择"),
                Action(id = "choose", label = "选择2"),
            ),
        )
        engine.render(card) {}

        assertNull(actionSeen)
    }
}
