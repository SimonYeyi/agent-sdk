package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.Serializable

/**
 * 卡片中间表示（Card IR）：一次工具调用产出的完整可交互 UI 规格。
 *
 * 卡片只经 UiSink 旁路对外广播，永不写入记忆；记忆里仅保留工具回执短文本。
 *
 * @param instanceId 卡片实例锚点，与工具调用 toolCallId 一一对应，会话恢复时按此归位
 * @param root 组件树根节点
 * @param actions 本卡片静态声明的动作白名单，渲染引擎只绑定白名单内动作
 * @param askAbout 是否询问卡：true 表示卡片向用户提问、等待其选择后继续（确认/决策型交互，
 *   如「确认下单」），回执固定为「等待用户回复」，模型后续轮次据此感知回合未完、待用户裁决；
 *   默认随卡片是否有按钮：有按钮视为询问卡，无按钮为信息展示卡（卡片即最终回复，按钮仅是
 *   可选延伸操作）；仅带延伸按钮的信息展示卡（如天气卡「查看更多」）应显式置 false
 */
@Serializable
public data class CardIr(
    public val instanceId: String,
    public val root: TreeNode,
    public val actions: List<Action> = emptyList(),
    public val askAbout: Boolean = actions.isNotEmpty(),
)
