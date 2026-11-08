package io.github.yeyi.agent.genui.adapter.default

import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.ActionEvent
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 自研后端的组件契约：组件名 + 渲染实现，动作由组件自己持有（节点自带或模板自有）。
 */
class DefaultRenderAdapterTest {

    private fun card(root: TreeNode): CardIr = CardIr(instanceId = "call_1", root = root)

    @Test
    fun `节点自带动作经出口回流 参数随动作带出`() {
        val adapter = DefaultRenderAdapter()
        adapter.register("Button") { _, _, _ -> }

        val events = mutableListOf<ActionEvent>()
        val root = TreeNode(
            component = "Button",
            action = Action("查询天气", payload = "珠海"),
        )
        val scope = adapter.scopeOf(card(root)) { events += it }

        scope.emit(root.action!!)

        assertEquals(1, events.size)
        assertEquals("call_1", events[0].instanceId)
        assertEquals("查询天气", events[0].action.label)
        assertEquals("珠海", events[0].action.payload)
        assertEquals("查询天气（珠海）", events[0].action.reflowText)
    }

    @Test
    fun `模板自有动作经出口回流 与节点动作无关`() {
        val adapter = DefaultRenderAdapter()
        adapter.register("Location") { _, _, _ -> }

        val events = mutableListOf<ActionEvent>()
        val root = TreeNode(component = "Location")
        val scope = adapter.scopeOf(card(root)) { events += it }

        scope.emit(Action("开启导航", payload = "珠海"))

        assertEquals(1, events.size)
        assertEquals("call_1", events[0].instanceId)
        assertEquals("开启导航", events[0].action.label)
        assertEquals("珠海", events[0].action.payload)
        assertEquals("开启导航（珠海）", events[0].action.reflowText)
    }

    @Test
    fun `空文案动作的节点整棵不渲染`() {
        val node = TreeNode(component = "Button", action = Action(" "))

        assertFalse(isRenderable(node))
    }

    @Test
    fun `有文案的动作与无动作的节点照常渲染`() {
        assertTrue(isRenderable(TreeNode(component = "Button", action = Action("查询珠海的天气"))))
        assertTrue(isRenderable(TreeNode(component = "Button")))
    }
}
