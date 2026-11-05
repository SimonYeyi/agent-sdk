package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.Serializable

/**
 * 卡片动作规格：回流事件的静态声明白名单。
 *
 * 动作集合由工具作者在 emit 卡片时声明，渲染引擎只绑定白名单内动作，杜绝自由发明。
 *
 * @param id 动作唯一 id，由 [ActionEvent.actionId] 携带，回流后 demo 映射为新一轮 AgentQuery
 * @param label 按钮/可点击区域文案
 */
@Serializable
public data class Action(
    public val id: String,
    public val label: String,
)
