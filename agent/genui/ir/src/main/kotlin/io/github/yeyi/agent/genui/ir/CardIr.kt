package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.Serializable

/**
 * 卡片中间表示（Card IR）：一次工具调用产出的完整可交互 UI 规格。
 *
 * @param instanceId 卡片实例标识
 * @param root 组件树根节点
 */
@Serializable
public data class CardIr(
    public val instanceId: String,
    public val root: TreeNode,
)
