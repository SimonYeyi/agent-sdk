package io.github.yeyi.agent.genui

public fun interface Renderer {
    /**
     * 组件渲染实现：框架无关，实现方可调起任意 UI 框架（Compose、View 等）。
     *
     * @param node 当前节点，实现方读取 [TreeNode.bindings] 渲染内容
     * @param action 已通过白名单校验的动作规格；null 表示该节点不可点击，实现方应渲染为静态内容
     * @param emit 触发动作回流：仅当 [action] 非 null 时可用，由引擎自动构造 [UiActionEvent]，实现方零组装
     */
    public fun render(node: TreeNode, action: Action?, emit: () -> Unit)
}
