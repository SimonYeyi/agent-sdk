package io.github.yeyi.agent.genui.ir

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 卡片动作校验：节点自带动作的文案非空。
 */
class CardIrValidatorTest {

    private val validator = CardIrValidator()

    @Test
    fun `无动作的卡片合法`() {
        assertTrue(validator.isValid(TreeNode(component = "Text")))
    }

    @Test
    fun `节点自带动作 文案非空时合法`() {
        val root = TreeNode(
            component = "Card",
            children = listOf(
                TreeNode(component = "Button", action = Action("刷新")),
                TreeNode(
                    component = "Column",
                    children = listOf(
                        TreeNode(component = "Button", action = Action("查询城市")),
                    ),
                ),
            ),
        )

        assertTrue(validator.isValid(root))
    }

    @Test
    fun `动作文案为空时整卡判非法`() {
        val root = TreeNode(component = "Button", action = Action(" "))

        assertFalse(validator.isValid(root))
    }

    @Test
    fun `子节点动作文案为空时整卡判非法`() {
        val root = TreeNode(
            component = "Card",
            children = listOf(
                TreeNode(component = "Button", action = Action("刷新")),
                TreeNode(component = "Button", action = Action("")),
            ),
        )

        assertFalse(validator.isValid(root))
    }

    @Test
    fun `文案相同的多个动作合法`() {
        val root = TreeNode(
            component = "Card",
            children = listOf(
                TreeNode(component = "Button", action = Action("刷新")),
                TreeNode(component = "Button", action = Action("刷新")),
            ),
        )

        assertTrue(validator.isValid(root))
    }
}
