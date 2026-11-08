package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 组件树节点。
 *
 * @param component 组件名，由渲染层注册表解释（"Text"、"Button"、"BarChart"…），IR 层不感知具体组件
 * @param bindings 组件属性（文本、数据、样式），渲染层读取；可点击节点的绑定数据将作为回流 [ActionEvent.payload]
 * @param children 子节点，由渲染引擎递归遍历
 * @param action 可点击节点自带的动作声明。动作由「拥有者」持有：通用组件路径下由本节点自带，
 *   模板组件路径下是组件自持的常量（不在 IR 里），工具不必感知——一个动作只有一处出处。
 */
@Serializable
public data class TreeNode(
    public val component: String,
    public val bindings: Map<String, JsonElement> = emptyMap(),
    public val children: List<TreeNode> = emptyList(),
    public val action: Action? = null,
)
