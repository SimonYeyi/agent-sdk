package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.Serializable

/**
 * 卡片动作规格：可点击物的动作声明。
 *
 * 动作由「拥有者」持有，一个动作只有一处出处：
 * - 通用组件路径：写在节点上（[TreeNode.action]），由工具产出；
 * - 模板组件路径：写在组件里，是组件自持的常量，工具不出现动作。
 *
 * 动作意图由工具调用层承载（卡片是工具调用的可视化呈现），回流只带 [label] 供模型共读——
 * 动作没有额外的程序侧标识：程序不需要按标识做逻辑，模型读的也只有文案。
 *
 * @param label 按钮/可点击区域文案（用户可见），随 [ActionEvent.action] 回流供模型理解
 */
@Serializable
public data class Action(
    public val label: String,
)
