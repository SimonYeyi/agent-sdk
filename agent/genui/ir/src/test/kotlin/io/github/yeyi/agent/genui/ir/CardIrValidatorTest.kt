package io.github.yeyi.agent.genui.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 卡片动作校验：动作由节点自带，卡内 id 唯一（同 id 必须同 label）。
 */
class CardIrValidatorTest {

    private val validator = CardIrValidator()

    @Test
    fun `收集组件树内节点自带动作`() {
        val root = TreeNode(
            component = "Card",
            children = listOf(
                TreeNode(component = "Button", action = Action(id = "refresh", label = "刷新")),
                TreeNode(
                    component = "Column",
                    children = listOf(
                        TreeNode(component = "Button", action = Action(id = "city", label = "查询城市")),
                    ),
                ),
            ),
        )

        val actions = assertNotNull(validator.validateActions(root))

        assertEquals(2, actions.size)
        assertEquals("刷新", actions["refresh"]?.label)
        assertEquals("查询城市", actions["city"]?.label)
    }

    @Test
    fun `动作 id 为空时整卡判非法`() {
        val root = TreeNode(component = "Button", action = Action(id = " ", label = "刷新"))

        assertNull(validator.validateActions(root))
    }

    @Test
    fun `同 id 不同 label 判非法`() {
        val root = TreeNode(
            component = "Card",
            children = listOf(
                TreeNode(component = "Button", action = Action(id = "refresh", label = "刷新")),
                TreeNode(component = "Button", action = Action(id = "refresh", label = "重新加载")),
            ),
        )

        assertNull(validator.validateActions(root))
    }

    @Test
    fun `同 id 同 label 视为同一动作`() {
        val root = TreeNode(
            component = "Card",
            children = listOf(
                TreeNode(component = "Button", action = Action(id = "refresh", label = "刷新")),
                TreeNode(component = "Button", action = Action(id = "refresh", label = "刷新")),
            ),
        )

        val actions = assertNotNull(validator.validateActions(root))

        assertEquals(1, actions.size)
    }

    @Test
    fun `无动作的卡片合法`() {
        val actions = assertNotNull(validator.validateActions(TreeNode(component = "Text")))

        assertEquals(0, actions.size)
    }
}
