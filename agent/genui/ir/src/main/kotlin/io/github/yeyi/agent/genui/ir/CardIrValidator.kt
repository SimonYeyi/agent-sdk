package io.github.yeyi.agent.genui.ir

/**
 * 卡片动作校验器：动作 id 非空；卡内同 id 必须同 label。
 *
 * 动作由「拥有者」持有：通用组件路径下由节点自带（[TreeNode.action]），
 * 模板组件路径下是模板自己的常量（写在渲染实现里），IR 层只校验节点自带的那部分。
 *
 * 节点动作的合法性与整卡降级是 genui 的安全红线，任何渲染后端（自研、A2UI 投影）都必须遵守，
 * 故独立为公开校验器，在协议层收口语义，供所有后端共用。
 */
public class CardIrValidator {

    /**
     * 收集组件树内节点自带的动作并校验。
     *
     * 同 id 的多个节点视为同一动作（多个触发点共享一个动作），label 必须一致。
     *
     * @return id 到动作规格的映射；卡内动作非法时返回 null，消费方应将整卡降级为纯展示（无动作）。
     */
    public fun validateActions(root: TreeNode): Map<String, Action>? {
        val actions = ArrayList<Action>()
        collect(root, actions)
        return validateActions(actions)
    }

    private fun collect(node: TreeNode, out: MutableList<Action>) {
        node.action?.let { out.add(it) }
        for (child in node.children) {
            collect(child, out)
        }
    }

    /**
     * 校验一批动作声明：id 非空；同 id 必须同声明。
     *
     * 只服务节点自带的那部分动作——模板组件的动作是模板自持的常量，由作者自控，不走这里。
     *
     * @return id 到动作规格的映射；声明非法（id 为空，或同 id 出现不同声明）时返回 null，
     * 消费方应将整卡降级为纯展示（无动作）。
     */
    private fun validateActions(actions: List<Action>): Map<String, Action>? {
        val byId = HashMap<String, Action>()
        for (action in actions) {
            if (action.id.isBlank()) return null
            val existing = byId[action.id]
            if (existing != null) {
                // 同 id 不同 label：动作语义歧义，判非法
                if (existing.label != action.label) return null
                continue
            }
            byId[action.id] = action
        }
        return byId
    }
}
