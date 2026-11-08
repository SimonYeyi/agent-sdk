package io.github.yeyi.agent.genui.adapter

import androidx.compose.runtime.Composable
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.ActionEvent

/**
 * 渲染后端插槽接口：genui 平台适配层的统一渲染入口。
 *
 * 自研后端（DefaultRenderAdapter）与三方协议后端（A2uiRenderAdapter）平级实现本接口，
 * demo 组装点一次性选择后端实现注入，业务代码统一走 [RenderCard] 消费卡片流，对后端差异无感。
 *
 * 实现方必须保证：入口渲染前先过 [io.github.yeyi.agent.genui.ir.CardIrValidator] 的节点动作校验，
 * 校验失败降级为纯展示（无动作），确保「使用无感」覆盖安全语义。
 */
public interface UiRenderAdapter {
    /**
     * @param card 待渲染的卡片 IR，经 UiSink 旁路到达，永不写入记忆
     * @param onAction 动作回流出口：用户点击动作时，实现方构造 [io.github.yeyi.agent.genui.ir.ActionEvent] 并经此回调，demo 组装为新一轮 AgentQuery
     */
    @Composable
    public fun RenderCard(card: CardIr, onAction: (ActionEvent) -> Unit)
}
