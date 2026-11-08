package io.github.yeyi.agent.genui.adapter.default

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.CardIrValidator
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.ir.ActionEvent
import io.github.yeyi.agent.genui.adapter.UiRenderAdapter
import kotlinx.serialization.json.JsonObject

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
 * 校验复用协议层 [CardIrValidator]，与 A2UI 投影后端共用同一安全语义。
 *
 * 组件渲染实现以 @Composable 形态注册；消费方只需注册实现后调用 [RenderCard]，后端差异无感。
 */
public class DefaultRenderAdapter(private val validator: CardIrValidator = CardIrValidator()) :
    UiRenderAdapter {

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
     * 校验卡内动作后递归渲染整棵组件树（先序），子节点自上而下线性排布。
     *
     * 卡内动作非法（id 为空、同 id 不同 label）时整卡降级为纯展示，无动作可回流。
     */
    @Composable
    override fun RenderCard(card: CardIr, onAction: (ActionEvent) -> Unit) {
        val valid = validator.validateActions(card.root) != null
        Column {
            RenderNode(card, card.root, valid, onAction)
        }
    }

    @Composable
    private fun RenderNode(
        card: CardIr,
        node: TreeNode,
        valid: Boolean,
        onAction: (ActionEvent) -> Unit,
    ) {
        val scope = scopeOf(card, node, valid, onAction)
        val effective = effectiveNode(node, valid)
        (registry[effective.component] ?: fallback)(effective, scope) {
            for (child in effective.children) {
                RenderNode(card, child, valid, onAction)
            }
        }
    }

    /**
     * 构造节点的动作出口：出口只负责带出锚点与载荷，动作值由组件自己持有。
     *
     * 载荷缺省取节点绑定数据——工具传给组件的业务数据随动作一起回流。
     */
    internal fun scopeOf(
        card: CardIr,
        node: TreeNode,
        valid: Boolean,
        onAction: (ActionEvent) -> Unit,
    ): ActionScope = ActionScope(
        onAction = { action, payload ->
            // 卡内动作非法时整卡降级：出口静默，无动作可回流
            if (valid) onAction(
                ActionEvent(
                    card.instanceId,
                    action,
                    payload ?: JsonObject(node.bindings)
                )
            )
        },
    )
}

/** 卡内动作非法时整卡降级：节点自带动作被剥离，组件只能渲染为静态内容。 */
internal fun effectiveNode(node: TreeNode, valid: Boolean): TreeNode =
    if (valid) node else node.copy(action = null)
