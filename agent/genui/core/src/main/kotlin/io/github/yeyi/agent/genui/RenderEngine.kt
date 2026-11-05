package io.github.yeyi.agent.genui

import kotlinx.serialization.json.JsonObject

/**
 * 渲染引擎：收口组件注册表、树遍历、未知组件降级与动作白名单绑定，框架无关。
 *
 * 组件渲染实现由外部（demo）注册，消费方只需注册实现后调用 [render]。
 * 白名单校验委托给 [CardIrValidator]，与投影后端（A2UI）共用同一安全语义。
 */
public class RenderEngine(public val validator: CardIrValidator = CardIrValidator()) {

    private val registry: MutableMap<String, Renderer> = HashMap()

    /** 未知组件降级实现，默认渲染为空内容；demo 可注册为文本块等兜底展示。 */
    private var fallback: Renderer = Renderer { _, _, _ -> }

    /** 注册组件渲染实现；同名组件后注册覆盖先注册。 */
    public fun register(component: String, renderer: Renderer) {
        registry[component] = renderer
    }

    /** 注册未知组件降级实现，保证未知组件不中断渲染。 */
    public fun registerFallback(renderer: Renderer) {
        fallback = renderer
    }

    /**
     * 校验动作白名单后递归渲染整棵组件树（先序）。
     *
     * 白名单本身非法（动作 id 为空或重复）时全卡降级为纯展示；节点动作未命中白名单时该节点渲染为静态内容。
     *
     * @param card 卡片 IR
     * @param emit 动作回流出口：用户点击命中白名单的动作节点时，引擎构造 [UiActionEvent] 并经此转发
     */
    public fun render(card: CardIr, emit: (UiActionEvent) -> Unit) {
        val whitelist = validator.validateActions(card.actions)
        renderNode(card, card.root, whitelist, emit)
    }

    private fun renderNode(
        card: CardIr,
        node: TreeNode,
        whitelist: Map<String, Action>?,
        emit: (UiActionEvent) -> Unit,
    ) {
        val action = node.action?.let { whitelist?.get(it) }
        val nodeEmit: () -> Unit = if (action != null) {
            { emit(UiActionEvent(card.instanceId, action.id, JsonObject(node.bindings))) }
        } else {
            {} // 不可点击：空操作，杜绝白名单外动作被绑定
        }
        (registry[node.component] ?: fallback).render(node, action, nodeEmit)
        for (child in node.children) {
            renderNode(card, child, whitelist, emit)
        }
    }
}
