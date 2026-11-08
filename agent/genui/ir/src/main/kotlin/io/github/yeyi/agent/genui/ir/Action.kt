package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.Serializable

/**
 * 卡片动作规格：可点击物的动作声明。
 *
 * 动作由「拥有者」持有，一个动作只有一处出处：
 * - 通用组件路径：写在节点上（[TreeNode.action]），由工具产出；
 * - 模板组件路径：写在组件里，是组件自持的常量，工具不出现动作。
 *
 * 动作只有文案与参数：意图由工具调用层承载（卡片是工具调用的可视化呈现），
 * 回流带的是 [reflowText]——label 是动作，payload 是动作的参数，两者都按字符串给出。
 *
 * @param label 按钮/可点击区域文案（用户可见），是动作本身
 * @param payload 动作的参数（字符串形式），由定义方在产出动作时直接给出；缺省表示无参数
 */
@Serializable
public data class Action(public val label: String, public val payload: String = "") {

    /**
     * 回流给模型的文案：label（payload），payload 为空时只有 label。
     *
     * 组合规则只在协议层这一处，两个渲染后端共享同一份口径。
     */
    public val reflowText: String
        get() = if (payload.isBlank()) label else "$label（$payload）"
}
