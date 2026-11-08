package io.github.yeyi.agent.genui.adapter.default

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.ActionEvent
import io.github.yeyi.agent.genui.adapter.UiRenderAdapter

/**
 * Compose 组件渲染实现。
 */
public typealias UiComponentRenderer = @Composable (
    node: TreeNode,
    /**
     *
     * 组件的动作出口：发起动作回流（见 [ActionScope]）
     */
    scope: ActionScope,
    /**
     * 子节点渲染插槽：
     * 容器组件（Card / Column / Row…）自行决定子节点排布
     * （间距、对齐、布局方向），叶子组件忽略该插槽；
     * 插槽只在组件当前渲染位置生效，与适配器先序递归语义等价
     */
    children: @Composable () -> Unit,
) -> Unit

/**
 * 自研后端适配器：以 [UiRenderAdapter] 插槽形态出场，与 A2UI 后端平级接入。
 *
 * 组件注册绑定平台渲染实现（@Composable），故渲染底座整体落在本适配器，ir 模块退化为纯协议层；
 * 组件渲染实现以 @Composable 形态注册；消费方只需注册实现后调用 [RenderCard]，后端差异无感。
 */
public class DefaultRenderAdapter : UiRenderAdapter {

    private val registry: MutableMap<String, UiComponentRenderer> = HashMap()

    /** 未知组件降级实现，默认渲染为空内容；demo 可注册为文本块等兜底展示。 */
    private var fallback: UiComponentRenderer = { _, _, _ -> }

    /**
     * 注册组件渲染实现；同名组件后注册覆盖先注册。
     *
     * 动作不在注册时声明：通用组件的动作来自节点自带（[TreeNode.action]），
     * 模板组件的动作是组件自己的常量、写在渲染实现里——协议面不区分两类，
     * 组件只管「点哪个按钮」和「按哪个动作回流」。
     *
     * @param component 组件名
     * @param renderer 组件渲染实现
     */
    public fun register(component: String, renderer: UiComponentRenderer) {
        registry[component] = renderer
    }

    /** 注册未知组件降级实现，保证未知组件不中断渲染。 */
    public fun registerFallback(renderer: UiComponentRenderer) {
        fallback = renderer
    }

    /**
     * 递归渲染整棵组件树（先序），子节点自上而下线性排布。
     */
    @Composable
    override fun RenderCard(card: CardIr, onAction: (ActionEvent) -> Unit) {
        Column {
            RenderNode(card, card.root, onAction)
        }
    }

    @Composable
    private fun RenderNode(
        card: CardIr,
        node: TreeNode,
        onAction: (ActionEvent) -> Unit,
    ) {
        // 空文案动作的节点整棵不渲染：动作文案就是它的可点内容，空了就没有可展示的东西
        if (!isRenderable(node)) return
        val scope = scopeOf(card, onAction)
        (registry[node.component] ?: fallback)(node, scope) {
            for (child in node.children) {
                RenderNode(card, child, onAction)
            }
        }
    }

    /**
     * 构造节点的动作出口：出口只负责带出锚点，动作值由组件自己持有。
     *
     * 动作的参数写在动作自身（[Action.payload]），回流文案由 [Action.reflowable] 给出。
     */
    internal fun scopeOf(
        card: CardIr,
        onAction: (ActionEvent) -> Unit,
    ): ActionScope = ActionScope(
        onAction = { action -> onAction(ActionEvent(card.instanceId, action)) },
    )
}

/** 空文案动作的节点不可渲染（整棵不渲染）。 */
internal fun isRenderable(node: TreeNode): Boolean = node.action?.label?.isBlank() != true
