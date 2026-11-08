package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 组件树节点。
 *
 * @param component 组件名，由渲染层注册表解释（"Text"、"Button"、"BarChart"…），IR 层不感知具体组件
 * @param bindings 组件属性（文本、数据、样式），渲染层读取
 * @param children 子节点，由渲染引擎递归遍历
 * @param action 可点击节点自带的动作声明。动作由「拥有者」持有：通用组件路径下由本节点自带，
 *   模板组件路径下是组件自持的常量（不在 IR 里），工具不必感知——一个动作只有一处出处；
 *   动作只有文案与参数，回流文案由 [Action.reflowable] 给出。
 */
@Serializable
public data class TreeNode(
    public val component: String,
    public val bindings: Map<String, JsonElement> = emptyMap(),
    public val children: List<TreeNode> = emptyList(),
    public val action: Action? = null,
)

/**
 * 节点是否可渲染：空文案动作的节点整棵不渲染。
 *
 * 动作文案就是它的可点内容，空了就没有可展示的东西。规则落在协议层，
 * 各渲染后端共用同一份口径（自研后端与 a2ui 后端都据此跳过整棵子树）。
 */
public fun TreeNode.isRenderable(): Boolean = action?.label?.isBlank() != true
