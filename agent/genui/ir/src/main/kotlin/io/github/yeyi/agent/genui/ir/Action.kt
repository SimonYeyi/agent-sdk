package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.Serializable

/**
 * 卡片动作规格：可点击物的动作声明。
 *
 * 动作由「拥有者」持有，一个动作只有一处出处：
 * - 通用组件路径：写在节点上（[TreeNode.action]），动作名每卡不同，由工具产出；
 * - 模板组件路径：写在组件里，是组件自持的常量，工具不出现动作。
 *
 * 动作意图由工具调用层承载（卡片是工具调用的可视化呈现），回流只带 [label] 供模型共读。
 *
 * @param id 动作唯一 id，渲染层绑定与回流路由的稳定标识（程序用，不需可读），随 [ActionEvent.action] 回流；
 *   节点自带的那部分由 [CardIrValidator] 校验（id 非空、同 id 必须同 label）
 * @param label 按钮/可点击区域文案（用户可见），随 [ActionEvent.action] 回流
 */
@Serializable
public data class Action(
    public val id: String,
    public val label: String,
)
