package io.github.yeyi.agent.genui.adapter.default

import io.github.yeyi.agent.genui.ir.Action
import kotlinx.serialization.json.JsonObject

/**
 * 组件的动作出口：组件渲染实现据此发起动作回流。
 *
 * 动作由「拥有者」声明，一个动作只有一处声明：
 * - 节点自带（通用组件路径，见 [io.github.yeyi.agent.genui.ir.TreeNode.action]）：
 *   动作名每卡不同，由工具写在节点上；
 * - 组件声明（模板组件路径）：动作是组件自己的常量，注册组件时声明，
 *   工具只出 root + 数据，可点击物与动作都留在模板里。
 *
 * 本作用域把两者合成一个出口：
 * - [action] 是本节点的有效动作，卡内动作非法时为 null（节点渲染为静态内容）；
 * - [emit] 按 id 发起动作，id 命中本节点动作或组件声明动作才发出，
 *   未命中静默无效——与「白名单外动作渲染为静态内容」同一套降级语义。
 */
public class UiActionScope internal constructor(
    /** 本节点的有效动作；卡内动作非法（id 为空、同 id 不同 label）时为 null。 */
    public val action: Action?,
    /** 组件注册时声明的动作，按 id 索引。 */
    private val declared: Map<String, Action>,
    /** 默认回流载荷：节点绑定数据（工具传给组件的业务数据）。 */
    private val payload: JsonObject,
    private val onAction: (Action, JsonObject) -> Unit,
) {

    /**
     * 发起动作回流。
     *
     * @param actionId 动作 id：命中本节点自带动作或组件声明动作才发出
     * @param payload 回流载荷，缺省为节点绑定数据
     */
    public fun emit(actionId: String, payload: JsonObject = this.payload) {
        val action = when {
            this.action?.id == actionId -> this.action
            else -> declared[actionId]
        } ?: return
        onAction(action, payload)
    }
}
