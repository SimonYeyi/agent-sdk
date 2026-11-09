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
 * 实现方必须保证：空文案动作的节点整棵不渲染（动作文案就是它的可点内容，空了就没有可展示的东西）。
 * 口径见 [io.github.yeyi.agent.genui.ir.isRenderable]。
 */
public interface UiRenderAdapter {
    /**
     * @param card 待渲染的卡片 IR，由消费端从工具结果 metadata 解码而来
     * @param onAction 动作回流出口：用户点击动作时，实现方构造 [io.github.yeyi.agent.genui.ir.ActionEvent] 并经此回调，demo 组装为新一轮 AgentQuery
     */
    @Composable
    public fun RenderCard(card: CardIr, onAction: (ActionEvent) -> Unit)
}
