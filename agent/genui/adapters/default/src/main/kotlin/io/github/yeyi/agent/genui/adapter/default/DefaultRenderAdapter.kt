package io.github.yeyi.agent.genui.adapter.default

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import io.github.yeyi.agent.genui.ir.Action
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
     * 组件的动作出口：发起动作回流（见 [UiActionScope]）
     */
    scope: UiActionScope,
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

    /** 组件契约：组件名 → 组件自己声明的动作。模板组件的可点击物与动作都归这里，工具不必感知。 */
    private val componentActions: MutableMap<String, Map<String, Action>> = HashMap()

    /** 未知组件降级实现，默认渲染为空内容；demo 可注册为文本块等兜底展示。 */
    private var fallback: UiComponentRenderer = { _, _, _ -> }

    /**
     * 注册组件渲染实现；同名组件后注册覆盖先注册。
     *
     * @param component 组件名
     * @param actions 组件自己声明的动作：模板组件路径下，可点击物与动作都留在组件里，
     *   工具只出 root + 数据；声明在注册时即校验（id 非空、同 id 不同 label 判非法），
     *   非法直接拒注册——这是代码错误，不该留到运行期。
     * @param renderer 组件渲染实现
     */
    public fun register(
        component: String,
        actions: List<Action> = emptyList(),
        renderer: UiComponentRenderer,
    ) {
        val declared = validator.validateActions(actions)
            ?: throw IllegalArgumentException("component '$component' declares invalid actions")
        registry[component] = renderer
        componentActions[component] = declared
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
        (registry[node.component] ?: fallback)(node, scope) {
            for (child in node.children) {
                RenderNode(card, child, valid, onAction)
            }
        }
    }

    /**
     * 构造节点的动作作用域：节点自带动作与组件声明动作合成一个出口。
     *
     * 载荷缺省取节点绑定数据——工具传给组件的业务数据随动作一起回流。
     */
    internal fun scopeOf(
        card: CardIr,
        node: TreeNode,
        valid: Boolean,
        onAction: (ActionEvent) -> Unit,
    ): UiActionScope = UiActionScope(
        action = if (valid) node.action else null,
        declared = if (valid) componentActions[node.component].orEmpty() else emptyMap(),
        payload = JsonObject(node.bindings),
        onAction = { action, payload -> onAction(ActionEvent(card.instanceId, action, payload)) },
    )
}
