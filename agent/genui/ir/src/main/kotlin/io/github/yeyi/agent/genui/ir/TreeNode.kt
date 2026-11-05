package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 组件树节点。
 *
 * @param component 组件名，由渲染层注册表解释（"Text"、"Button"、"BarChart"…），IR 层不感知具体组件
 * @param bindings 组件属性（文本、数据、样式），渲染层读取；可点击节点的绑定数据将作为回流 [ActionEvent.payload]
 * @param children 子节点，由渲染引擎递归遍历
 * @param action 可点击节点绑定的动作 id，必须命中 [CardIr.actions] 白名单，否则渲染为静态内容
 */
@Serializable
public data class TreeNode(
    public val component: String,
    public val bindings: Map<String, JsonElement> = emptyMap(),
    public val children: List<TreeNode> = emptyList(),
    public val action: String? = null,
)
