package io.github.yeyi.agent.genui.adapter.default

import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.ActionEvent
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * 自研后端的组件契约：组件名 + 动作集 + 渲染实现，动作由组件自己声明。
 */
class DefaultRenderAdapterTest {

    private fun card(root: TreeNode): CardIr = CardIr(instanceId = "call_1", root = root)

    @Test
    fun `组件声明的动作经作用域回流 载荷为节点绑定数据`() {
        val adapter = DefaultRenderAdapter()
        adapter.register("Location", listOf(Action("navigate", "开启导航"))) { _, _, _ -> }

        val events = mutableListOf<ActionEvent>()
        val root = TreeNode(
            component = "Location",
            bindings = mapOf("city" to JsonPrimitive("珠海")),
        )
        val scope = adapter.scopeOf(card(root), root, valid = true) { events += it }

        scope.emit("navigate")

        assertEquals(1, events.size)
        assertEquals("call_1", events[0].instanceId)
        assertEquals("navigate", events[0].action.id)
        assertEquals("开启导航", events[0].action.label)
        assertEquals("珠海", events[0].payload["city"]?.toString()?.trim('"'))
    }

    @Test
    fun `节点自带的动作同样可回流`() {
        val adapter = DefaultRenderAdapter()
        adapter.register("Button") { _, _, _ -> }

        val events = mutableListOf<ActionEvent>()
        val root = TreeNode(
            component = "Button",
            bindings = mapOf("city" to JsonPrimitive("上海")),
            action = Action("city_shanghai", "查询上海的天气"),
        )
        val scope = adapter.scopeOf(card(root), root, valid = true) { events += it }

        scope.emit("city_shanghai")

        assertEquals(1, events.size)
        assertEquals("查询上海的天气", events[0].action.label)
    }

    @Test
    fun `卡内动作非法时整卡降级 组件动作也不可用`() {
        val adapter = DefaultRenderAdapter()
        adapter.register("Location", listOf(Action("navigate", "开启导航"))) { _, _, _ -> }

        val events = mutableListOf<ActionEvent>()
        val root = TreeNode(component = "Location")
        // valid=false：卡内动作非法（id 为空或同 id 不同 label）时的整卡降级
        val scope = adapter.scopeOf(card(root), root, valid = false) { events += it }

        scope.emit("navigate")

        assertTrue(events.isEmpty())
    }

    @Test
    fun `组件声明非法动作时拒注册`() {
        val adapter = DefaultRenderAdapter()

        assertFailsWith<IllegalArgumentException> {
            adapter.register("Location", listOf(Action(" ", "开启导航"))) { _, _, _ -> }
        }
    }
}
