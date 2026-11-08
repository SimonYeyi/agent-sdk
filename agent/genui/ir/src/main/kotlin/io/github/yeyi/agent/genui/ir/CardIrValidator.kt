package io.github.yeyi.agent.genui.ir

/**
 * 卡片动作校验器：节点自带动作的文案非空。
 *
 * 动作由「拥有者」持有：通用组件路径下由节点自带（[TreeNode.action]），
 * 模板组件路径下是模板自己的常量（写在渲染实现里），IR 层只校验节点自带的那部分。
 *
 * 节点动作的合法性与整卡降级是 genui 的安全红线，任何渲染后端（自研、A2UI 投影）都必须遵守，
 * 故独立为公开校验器，在协议层收口语义，供所有后端共用。
 */
public class CardIrValidator {

    /**
     * 校验卡内节点自带的动作。
     *
     * @return 卡内动作是否合法；非法（文案为空）时消费方应将整卡降级为纯展示（无动作）。
     */
    public fun isValid(root: TreeNode): Boolean {
        if (root.action?.label?.isBlank() == true) return false
        for (child in root.children) {
            if (!isValid(child)) return false
        }
        return true
    }
}
