package io.github.yeyi.agent.genui.adapter.default

import io.github.yeyi.agent.genui.ir.Action

/**
 * 组件的动作出口：组件渲染实现据此发起动作回流。
 *
 * 动作值由调用方给出，出口不关心它从哪来：
 * - 节点自带（通用组件路径）：动作值取自 [io.github.yeyi.agent.genui.ir.TreeNode.action]，
 *   动作文案每卡不同，由工具写在节点上；
 * - 模板自有（模板组件路径）：动作是组件自己的常量，写在模板里，工具侧不出现动作。
 *
 * 出口只负责把动作交回渲染层——能发出什么动作由组件代码决定。
 */
public class ActionScope internal constructor(private val onAction: (Action) -> Unit) {

    /**
     * 发起动作回流。
     *
     * @param action 动作声明；节点自带动作由渲染层就地判空（空文案不绑定点击），
     *   模板自有动作是作者自持的常量
     */
    public fun emit(action: Action): Unit = onAction(action)
}
