package io.github.yeyi.agent.genui.ir

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreeNodeTest {

    @Test
    fun `空文案动作的节点不可渲染`() {
        assertFalse(TreeNode(component = "Button", action = Action(" ")).isRenderable())
    }

    @Test
    fun `有文案的动作与无动作的节点照常渲染`() {
        assertTrue(TreeNode(component = "Button", action = Action("查询珠海的天气")).isRenderable())
        assertTrue(TreeNode(component = "Button").isRenderable())
    }
}
