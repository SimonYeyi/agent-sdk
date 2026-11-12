package io.github.yeyi.agent.genui.adapter.a2ui

import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor as createA2uiMessageProcessor
import androidx.a2ui.engine.catalog.A2uiCoreCatalog
import androidx.a2ui.model.processor.A2uiMessageProcessor
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.a2ui.model.protocol.A2uiCreateSurfaceMessage
import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.material3.a2ui.A2uiSurfaceDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.ActionEvent
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.ir.isRenderable
import io.github.yeyi.agent.genui.adapter.UiRenderAdapter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * A2UI 后端适配器：CardIr → A2UI v0.9 协议投影 + androidx.a2ui 官方引擎状态机 + 官方 Compose 渲染。
 *
 * 渲染链路：[RenderCard] 是「使用无感」的插槽入口，空文案动作的节点整棵不投影
 * （口径见 [isRenderable]）；
 * 投影结果经官方 compose-ui 的 [androidx.a2ui.compose.ui.A2uiMessageProcessor] 工厂落地为
 * 协议消息流（createSurface + updateComponents 扁平邻接表），官方引擎维护 surface 状态；
 * 适配器从 [A2uiMessageProcessor.activeSurfaces] 收集本卡 surface，交给
 * androidx.compose.material3 的 [A2uiSurface] 渲染（自动处理加载/错误/成功状态与过渡动画）。
 *
 * 动作回流：用户点击节点自带动作后，官方渲染器经引擎 outboundEvents 发射
 * [A2uiClientEventMessage]，本适配器将其映射回 [ActionEvent] 并转发给卡片对应的 onAction 回调，
 * demo 侧与自研后端收口完全一致。
 *
 * 投影口径（IR 是后端中立的词汇，官方 Material 目录有自己的必填契约，缺项会被引擎丢弃）：
 * - Text：`content` → 官方 `text`（必填），`style` → 官方 `variant`；
 * - Button：动作 → 官方 `action`（必填），可见文案合成为一个 Text 当官方 `child`（必填，官方只收单个）；
 *   无动作的 Button 按静态文本投影（官方 Button 的 action 必填，无动作即非按钮）；
 * - Column/Row：子节点 → 官方 `children`（必填），`arrangement` → 官方 `justify`，
 *   `horizontalAlignment`/`verticalAlignment` → 官方 `align`；
 * - Card：官方 `child` 只收单个组件，多个子节点用 Column 包一层；
 * - 官方目录没有的组件（Badge、模板组件等）不投影——投影了也会因缺必填属性被引擎丢弃。
 *
 * 后端能力差异：模板卡（root 为自研模板组件）在本后端不可用——组件名不在官方目录内，
 * 不投影；模板卡只在自研后端生效。
 *
 * @param catalog 组件目录契约：需同时实现 [A2uiCatalog]（渲染契约，A2uiSurface 据此渲染）与
 *   [A2uiCoreCatalog]（引擎 schema 校验契约）。官方 [androidx.a2ui.compose.ui.A2uiCatalog]
 *   构建产物（如 material3-a2ui 的 Material 目录）同时实现两者，构造时显式校验防伪实现
 * @param scope 引擎消息循环与动作回流收集的运行域，调用方持有生命周期
 */
public class A2uiRenderAdapter(
    public val catalog: A2uiCatalog,
    public val scope: CoroutineScope,
) : UiRenderAdapter {

    init {
        require(catalog is A2uiCoreCatalog) {
            "A2uiRenderAdapter 需要引擎可用的目录：catalog 必须同时实现 A2uiCoreCatalog，" +
                "官方 A2uiCatalog 构建产物（A2uiCatalog(...) / MaterialA2uiBasicCatalogV1()）满足该契约"
        }
    }

    /** 官方引擎消息处理器：维护 surface 状态、schema 校验、增量更新与动作事件流。 */
    public val processor: A2uiMessageProcessor = createA2uiMessageProcessor(
        catalogs = listOf(catalog),
        interceptors = emptyList(),
    )

    private val sessions: MutableMap<String, CardSession> = ConcurrentHashMap()

    init {
        // 引擎内部消息队列消费循环：processMessage 只投递，实际路由在 collectMessages 中执行
        scope.launch { processor.collectMessages() }
        // 动作回流收集：客户端事件消息映射回 UiActionEvent 转发给对应卡片回调
        scope.launch { processor.outboundEvents.collect(::handleOutbound) }
    }

    /**
     * 将卡片投影为 A2UI 协议消息并投递进引擎状态机。
     *
     * 空文案动作的节点整棵不渲染：不落协议消息；root 不可渲染时整卡不投影（不建 surface）。
     * 动作由节点自带，节点动作即协议 action 属性；同一 [CardIr.instanceId] 只投影一次
     * （引擎消息与节点映射幂等），重复调用仅更新动作回调。
     *
     * @param card 待渲染的卡片 IR
     * @param onAction 动作回流出口：用户点击节点自带动作时，映射为 [ActionEvent] 并经此回调
     */
    public fun project(card: CardIr, onAction: (ActionEvent) -> Unit) {
        // root 不可渲染（空文案动作）时整卡没有可投影的内容：
        // 不建 surface，也不留 session（空 surface 会滞留在引擎状态机里）
        if (!card.root.isRenderable()) return
        val session = sessions.computeIfAbsent(card.instanceId) { CardSession(card.instanceId) }
        session.onAction = onAction
        if (!session.projected.compareAndSet(false, true)) return

        val payloads = ArrayList<A2uiComponentPayload>()
        session.nodeActions.clear()
        val idGen = AtomicInteger(1)
        flatten(card.root, ROOT_COMPONENT_ID, session, payloads, idGen)

        processor.processMessage(
            A2uiCreateSurfaceMessage(
                card.instanceId,
                catalog.id,
                emptyMap(),
                false
            )
        )
        processor.processMessage(A2uiUpdateComponentsMessage(card.instanceId, payloads))
    }

    @Composable
    override fun RenderCard(card: CardIr, onAction: (ActionEvent) -> Unit) {
        project(card, onAction)
        val surface = processor.activeSurfaces
            .collectAsState()
            .value
            .firstOrNull { it.id == card.instanceId }
        if (surface != null) {
            A2uiSurface(
                surfaceModel = surface,
                modifier = Modifier,
                loadingContent = { A2uiSurfaceDefaults.LoadingIndicator() },
                errorContent = { A2uiSurfaceDefaults.ErrorFallback(it) },
                transitionSpec = A2uiSurfaceDefaults.transitionSpec,
            )
        }
    }

    /**
     * 按官方目录的组件契约投影一个节点。
     *
     * 官方目录对每个组件有各自的必填属性，缺项的组件会被引擎校验丢弃；
     * 故这里按组件名分派到各自的投影规则，而不是把 IR 的 bindings 原样透传。
     */
    private fun flatten(
        node: TreeNode,
        componentId: String,
        session: CardSession,
        out: MutableList<A2uiComponentPayload>,
        idGen: AtomicInteger,
    ) {
        // 空文案动作的节点整棵不渲染：不落协议消息，其子节点一并丢弃
        if (!node.isRenderable()) return
        when (node.component) {
            COMPONENT_TEXT -> flattenText(node, componentId, out)
            COMPONENT_BUTTON -> flattenButton(node, componentId, session, out, idGen)
            COMPONENT_COLUMN -> flattenContainer(node, componentId, COMPONENT_COLUMN, session, out, idGen)
            COMPONENT_ROW -> flattenContainer(node, componentId, COMPONENT_ROW, session, out, idGen)
            COMPONENT_CARD -> flattenCard(node, componentId, session, out, idGen)
            COMPONENT_DIVIDER -> out.add(A2uiComponentPayload(componentId, COMPONENT_DIVIDER, emptyMap()))
            else -> skipUnsupported(node)
        }
    }

    /** Text：官方 `text` 必填；`style` 映射为官方 `variant`（官方只有 h1~h5/caption/body）。
     *  行内 span（Text 的 Span children）退化为拼接纯文本——a2ui 协议无行内样式概念。 */
    private fun flattenText(
        node: TreeNode,
        componentId: String,
        out: MutableList<A2uiComponentPayload>,
        fallbackText: String = "",
    ) {
        val spanChildren = node.children.filter { it.component == "Span" }
        val content = if (spanChildren.isNotEmpty()) {
            spanChildren.joinToString("") { it.bindings.text("text") }
        } else {
            node.bindings.text("text")
        }.ifEmpty { fallbackText }
        // 官方 text 必填：没有文本就没有可渲染的东西
        if (content.isEmpty()) return
        val properties = LinkedHashMap<String, Any?>()
        properties[A2UI_KEY_TEXT] = content
        node.bindings.text("style").takeIf { it.isNotEmpty() }
            ?.let { style -> textVariant(style) }
            ?.let { variant -> properties[A2UI_KEY_VARIANT] = variant }
        out.add(A2uiComponentPayload(componentId, COMPONENT_TEXT, properties))
    }

    /**
     * Button：官方 `action` 与 `child` 都必填，且 child 只收单个组件。
     *
     * 可见文案由动作文案合成一个 Text 当 child（动作文案就是按钮上要显示的字）；
     * 无动作的 Button 不是官方意义上的按钮，按静态文本投影。
     */
    private fun flattenButton(
        node: TreeNode,
        componentId: String,
        session: CardSession,
        out: MutableList<A2uiComponentPayload>,
        idGen: AtomicInteger,
    ) {
        val action = node.action
        if (action == null) {
            flattenText(node, componentId, out, fallbackText = node.bindings.text("text"))
            return
        }
        val properties = LinkedHashMap<String, Any?>()
        properties[A2UI_KEY_ACTION] = mapOf(
            A2UI_KEY_EVENT to mapOf(
                A2UI_KEY_NAME to action.label,
                A2UI_KEY_CONTEXT to mapOf(A2UI_KEY_PAYLOAD to action.payload),
            ),
        )
        val labelId = "node-" + idGen.getAndIncrement()
        properties[A2UI_KEY_CHILD] = labelId
        out.add(A2uiComponentPayload(labelId, COMPONENT_TEXT, mapOf(A2UI_KEY_TEXT to action.label)))
        node.bindings.text("variant").takeIf { it.isNotEmpty() }
            ?.let { variant -> buttonVariant(variant) }
            ?.let { variant -> properties[A2UI_KEY_VARIANT] = variant }
        session.nodeActions[componentId] = action
        out.add(A2uiComponentPayload(componentId, COMPONENT_BUTTON, properties))
    }

    /** Column/Row：官方 `children` 必填（空容器传空数组），`arrangement` → `justify`，对齐 → `align`。 */
    private fun flattenContainer(
        node: TreeNode,
        componentId: String,
        type: String,
        session: CardSession,
        out: MutableList<A2uiComponentPayload>,
        idGen: AtomicInteger,
    ) {
        val properties = LinkedHashMap<String, Any?>()
        val children = node.children.filter { it.isRenderable() }
        val childIds = children.map { "node-" + idGen.getAndIncrement() }
        properties[A2UI_KEY_CHILDREN] = childIds
        node.bindings.text("arrangement").takeIf { it.isNotEmpty() }
            ?.let { arrangement -> justify(arrangement) }
            ?.let { justify -> properties[A2UI_KEY_JUSTIFY] = justify }
        val alignment = if (type == COMPONENT_COLUMN) {
            node.bindings.text("horizontalAlignment")
        } else {
            node.bindings.text("verticalAlignment")
        }
        alignment.takeIf { it.isNotEmpty() }
            ?.let { value -> align(value) }
            ?.let { align -> properties[A2UI_KEY_ALIGN] = align }
        for (index in children.indices) {
            flatten(children[index], childIds[index], session, out, idGen)
        }
        out.add(A2uiComponentPayload(componentId, type, properties))
    }

    /** Card：官方 `child` 只收单个组件，多个子节点用 Column 包一层（官方目录的既定做法）。 */
    private fun flattenCard(
        node: TreeNode,
        componentId: String,
        session: CardSession,
        out: MutableList<A2uiComponentPayload>,
        idGen: AtomicInteger,
    ) {
        val properties = LinkedHashMap<String, Any?>()
        val children = node.children.filter { it.isRenderable() }
        val childIds = children.map { "node-" + idGen.getAndIncrement() }
        val wrapperId = "node-" + idGen.getAndIncrement()
        properties[A2UI_KEY_CHILD] = wrapperId
        out.add(A2uiComponentPayload(wrapperId, COMPONENT_COLUMN, mapOf(A2UI_KEY_CHILDREN to childIds)))
        for (index in children.indices) {
            flatten(children[index], childIds[index], session, out, idGen)
        }
        out.add(A2uiComponentPayload(componentId, COMPONENT_CARD, properties))
    }

    /** 官方目录里没有的组件：投影过去也会因缺必填属性被引擎丢弃，故不投影。 */
    private fun skipUnsupported(node: TreeNode) {
        println("[$TAG] 官方目录无对应组件，不投影：${node.component}")
    }

    private fun handleOutbound(message: Any) {
        when (message) {
            is A2uiClientEventMessage -> dispatchClientEvent(message)
            // 引擎的校验错误（如组件缺必填属性）走客户端错误消息，不处理就是静默丢弃
            is A2uiClientErrorMessage -> println("[$TAG] 引擎报错：${message.message} ${message.context}")
        }
    }

    internal fun dispatchClientEvent(message: A2uiClientEventMessage) {
        val session = sessions[message.surfaceId] ?: return
        val action = session.nodeActions[message.componentId] ?: return
        session.onAction?.invoke(ActionEvent(session.instanceId, action))
    }

    private class CardSession(
        val instanceId: String,
    ) {
        val projected: AtomicBoolean = AtomicBoolean(false)
        val nodeActions: MutableMap<String, Action> = ConcurrentHashMap()

        @Volatile
        var onAction: ((ActionEvent) -> Unit)? = null
    }

    private companion object {
        /** A2UI v0.9 组件扁平邻接表：根组件 id 约定。 */
        const val ROOT_COMPONENT_ID = "root"
        const val TAG = "A2uiRenderAdapter"

        /** IR 组件名（后端中立词汇）与官方目录组件名同名，逐个按官方契约投影。 */
        const val COMPONENT_TEXT = "Text"
        const val COMPONENT_BUTTON = "Button"
        const val COMPONENT_COLUMN = "Column"
        const val COMPONENT_ROW = "Row"
        const val COMPONENT_CARD = "Card"
        const val COMPONENT_DIVIDER = "Divider"

        const val A2UI_KEY_CHILDREN = "children"
        const val A2UI_KEY_CHILD = "child"
        const val A2UI_KEY_ACTION = "action"
        const val A2UI_KEY_EVENT = "event"
        const val A2UI_KEY_NAME = "name"
        const val A2UI_KEY_CONTEXT = "context"
        const val A2UI_KEY_PAYLOAD = "payload"
        const val A2UI_KEY_TEXT = "text"
        const val A2UI_KEY_VARIANT = "variant"
        const val A2UI_KEY_JUSTIFY = "justify"
        const val A2UI_KEY_ALIGN = "align"
    }
}

/** 读取绑定里的字符串值。 */
private fun Map<String, JsonElement>.text(key: String): String =
    (this[key] as? JsonPrimitive)?.content ?: ""

/**
 * IR 的文本样式 → 官方 Text 变体。
 *
 * IR 用 M3 typography 档位名（headlineLarge/.../labelSmall），官方只有 h1~h5/caption/body，
 * 按字号档取最接近的一档。
 */
private fun textVariant(style: String): String? = when (style.trim()) {
    "headlineLarge" -> "h1"
    "headlineMedium" -> "h2"
    "headlineSmall" -> "h3"
    "titleLarge" -> "h3"
    "titleMedium" -> "h4"
    "titleSmall" -> "h5"
    "bodyLarge" -> "body"
    "bodyMedium" -> "body"
    "bodySmall" -> "caption"
    "labelMedium" -> "caption"
    "labelSmall" -> "caption"
    else -> null
}

/**
 * IR 的按钮样式 → 官方 Button 变体。
 *
 * IR 用 PascalCase（Primary/Secondary/Bordered/Destructive/Plain），官方只有
 * primary/secondary/borderless 三档；Bordered、Destructive 无对应，取次强调的 secondary。
 */
private fun buttonVariant(variant: String): String? = when (variant.trim()) {
    "Primary" -> "primary"
    "Secondary" -> "secondary"
    "Plain" -> "borderless"
    "Bordered", "Destructive" -> "secondary"
    else -> null
}

/**
 * IR 的排列 → 官方 justify。
 *
 * IR 用 Compose [androidx.compose.foundation.layout.Arrangement] 枚举名（Start/Center/End/SpaceBetween/SpaceAround/SpaceEvenly/
 * Top/Bottom），官方 justify 取值 start/center/end/spaceBetween/spaceAround/spaceEvenly。
 * Top/Bottom（Column 的纵向排列）映射到 start/end——官方 justify 只有横向语义。
 */
private fun justify(arrangement: String): String? = when (arrangement.trim()) {
    "Start", "Top" -> "start"
    "Center" -> "center"
    "End", "Bottom" -> "end"
    "SpaceBetween" -> "spaceBetween"
    "SpaceAround" -> "spaceAround"
    "SpaceEvenly" -> "spaceEvenly"
    else -> null
}

/**
 * IR 的对齐 → 官方 align。
 *
 * IR 用 Compose [androidx.compose.ui.Alignment] 枚举名（Start/CenterHorizontally/CenterVertically/End/Top/Bottom），
 * 官方 align 取值 start/center/end/stretch。
 */
private fun align(alignment: String): String? = when (alignment.trim()) {
    "Start", "Top" -> "start"
    "Center", "CenterHorizontally", "CenterVertically" -> "center"
    "End", "Bottom" -> "end"
    else -> null
}
