package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.Serializable

/**
 * 卡片中间表示（Card IR）：一次工具调用产出的完整可交互 UI 规格。
 *
 * 卡片只经 UiSink 旁路对外广播，永不写入记忆；记忆里仅保留工具回执短文本。
 *
 * @param instanceId 卡片实例锚点，与工具调用 toolCallId 一一对应，会话恢复时按此归位
 * @param root 组件树根节点
 */
@Serializable
public data class CardIr(
    public val instanceId: String,
    public val root: TreeNode,
)
