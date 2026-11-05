package io.github.yeyi.agent.genui.adapter.default

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import io.github.yeyi.agent.genui.Action
import io.github.yeyi.agent.genui.CardIr
import io.github.yeyi.agent.genui.CardIrValidator
import io.github.yeyi.agent.genui.RenderEngine
import io.github.yeyi.agent.genui.TreeNode
import io.github.yeyi.agent.genui.UiActionEvent
import io.github.yeyi.agent.genui.adapter.UiRenderAdapter
import kotlinx.serialization.json.JsonObject

/** Compose 组件渲染实现签名：*/
public typealias UiComponentRenderer = @Composable (node: TreeNode, action: Action?, emit: () -> Unit) -> Unit

/**
 * 自研后端适配器：以 [UiRenderAdapter] 插槽形态出场，与 A2UI 后端平级接入。
 *
 * 本适配器承载 Compose 平台的自研渲染实现（组件渲染实现注册 + 组件树递归渲染），
 * 白名单/降级校验复用 [RenderEngine.validator]（[CardIrValidator]），与 core 自研底座共用同一安全语义。
 *
 * 组件渲染实现以 @Composable 形态注册（区别于 core 的命令式 [io.github.yeyi.agent.genui.Renderer]，
 * 后者为框架无关底座，供非 Compose 场景直接使用）；消费方只需注册实现后调用 [RenderCard]，后端差异无感。
 */
public class DefaultRenderAdapter(private val engine: RenderEngine) : UiRenderAdapter {

    private val registry: MutableMap<String, UiComponentRenderer> = HashMap()

    /** 未知组件降级实现，默认渲染为空内容；demo 可注册为文本块等兜底展示。 */
    private var fallback: UiComponentRenderer = { _, _, _ -> }

    /** 注册组件渲染实现；同名组件后注册覆盖先注册。 */
    public fun register(component: String, renderer: UiComponentRenderer) {
        registry[component] = renderer
    }

    /** 注册未知组件降级实现，保证未知组件不中断渲染。 */
    public fun registerFallback(renderer: UiComponentRenderer) {
        fallback = renderer
    }

    /**
     * 校验动作白名单后递归渲染整棵组件树（先序），子节点自上而下线性排布。
     *
     * 白名单本身非法（动作 id 为空或重复）时全卡降级为纯展示；节点动作未命中白名单时该节点渲染为静态内容。
     */
    @Composable
    override fun RenderCard(card: CardIr, onAction: (UiActionEvent) -> Unit) {
        val whitelist = engine.validator.validateActions(card.actions)
        Column {
            RenderNode(card, card.root, whitelist, onAction)
        }
    }

    @Composable
    private fun RenderNode(
        card: CardIr,
        node: TreeNode,
        whitelist: Map<String, Action>?,
        onAction: (UiActionEvent) -> Unit,
    ) {
        val action = node.action?.let { whitelist?.get(it) }
        val nodeEmit: () -> Unit = if (action != null) {
            { onAction(UiActionEvent(card.instanceId, action.id, JsonObject(node.bindings))) }
        } else {
            {} // 不可点击：空操作，杜绝白名单外动作被绑定
        }
        (registry[node.component] ?: fallback)(node, action, nodeEmit)
        for (child in node.children) {
            RenderNode(card, child, whitelist, onAction)
        }
    }
}
