package io.github.yeyi.agent.genui.adapter.default

import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.ActionEvent
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * 自研后端的组件契约：组件名 + 渲染实现，动作由组件自己持有（节点自带或模板自有）。
 */
class DefaultRenderAdapterTest {

    private fun card(root: TreeNode): CardIr = CardIr(instanceId = "call_1", root = root)

    @Test
    fun `节点自带动作经出口回流 载荷为节点绑定数据`() {
        val adapter = DefaultRenderAdapter()
        adapter.register("Button") { _, _, _ -> }

        val events = mutableListOf<ActionEvent>()
        val root = TreeNode(
            component = "Button",
            bindings = mapOf("city" to JsonPrimitive("珠海")),
            action = Action("查询珠海的天气"),
        )
        val scope = adapter.scopeOf(card(root), root, valid = true) { events += it }

        scope.emit(root.action!!)

        assertEquals(1, events.size)
        assertEquals("call_1", events[0].instanceId)
        assertEquals("查询珠海的天气", events[0].action.label)
        assertEquals("珠海", events[0].payload["city"]?.toString()?.trim('"'))
    }

    @Test
    fun `模板自有动作经出口回流 与节点动作无关`() {
        val adapter = DefaultRenderAdapter()
        adapter.register("Location") { _, _, _ -> }

        val events = mutableListOf<ActionEvent>()
        val root = TreeNode(
            component = "Location",
            bindings = mapOf("city" to JsonPrimitive("珠海")),
        )
        val scope = adapter.scopeOf(card(root), root, valid = true) { events += it }

        scope.emit(Action("开启导航"))

        assertEquals(1, events.size)
        assertEquals("call_1", events[0].instanceId)
        assertEquals("开启导航", events[0].action.label)
        assertEquals("珠海", events[0].payload["city"]?.toString()?.trim('"'))
    }

    @Test
    fun `卡内动作非法时整卡降级 出口静默`() {
        val adapter = DefaultRenderAdapter()
        adapter.register("Location") { _, _, _ -> }

        val events = mutableListOf<ActionEvent>()
        val root = TreeNode(component = "Location")
        // valid=false：卡内动作非法（文案为空）时的整卡降级
        val scope = adapter.scopeOf(card(root), root, valid = false) { events += it }

        scope.emit(Action("开启导航"))

        assertTrue(events.isEmpty())
    }

    @Test
    fun `卡内动作非法时节点动作被剥离`() {
        val node = TreeNode(component = "Button", action = Action("查询珠海的天气"))

        assertNull(effectiveNode(node, valid = false).action)
        assertEquals("查询珠海的天气", effectiveNode(node, valid = true).action?.label)
    }
}
