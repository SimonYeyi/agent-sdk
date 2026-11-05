package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.Serializable

/**
 * 卡片动作规格：回流事件的静态声明白名单。
 *
 * 动作集合由工具作者在 emit 卡片时声明，渲染引擎只绑定白名单内动作，杜绝自由发明。
 * 动作意图由工具调用层承载（卡片是工具调用的可视化呈现），回流只带 [label] 供模型共读。
 *
 * @param id 动作唯一 id，渲染层绑定与回流路由的稳定标识（程序用，不需可读），随 [ActionEvent.action] 回流
 * @param label 按钮/可点击区域文案（用户可见），同一卡片上应语义唯一，随 [ActionEvent.action] 回流
 */
@Serializable
public data class Action(
    public val id: String,
    public val label: String,
)
