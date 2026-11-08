package io.github.yeyi.agent.genui.adapter.default

import io.github.yeyi.agent.genui.ir.Action
import kotlinx.serialization.json.JsonObject

/**
 * 组件的动作出口：组件渲染实现据此发起动作回流。
 *
 * 动作值由调用方给出，出口不关心它从哪来：
 * - 节点自带（通用组件路径）：动作值取自 [io.github.yeyi.agent.genui.ir.TreeNode.action]，
 *   动作名每卡不同，由工具写在节点上，受卡级校验保护；
 * - 模板自有（模板组件路径）：动作是组件自己的常量，写在模板里，工具侧不出现动作。
 *
 * 出口只负责把动作连同锚点与载荷交回渲染层，不做动作白名单——能发出什么动作
 * 由组件代码决定；模型可控的动作（节点自带）仍受卡级校验约束。
 */
public class ActionScope internal constructor(private val onAction: (Action, JsonObject?) -> Unit) {

    /**
     * 发起动作回流。
     *
     * @param action 动作声明；id 非空是约定——节点自带动作由卡级校验保证，
     *   模板自有动作是作者自持的常量，约定非空即可，出口不再重复校验
     * @param payload 回流载荷，缺省由适配器按节点绑定数据填充
     */
    public fun emit(action: Action, payload: JsonObject? = null): Unit = onAction(action, payload)
}
