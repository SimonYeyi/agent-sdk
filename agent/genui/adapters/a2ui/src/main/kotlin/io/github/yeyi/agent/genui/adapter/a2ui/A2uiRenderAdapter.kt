package io.github.yeyi.agent.genui.adapter.a2ui

import androidx.a2ui.engine.catalog.A2uiCoreCatalog
import androidx.a2ui.engine.platform.A2uiCoreComponentRegistry
import androidx.a2ui.engine.platform.A2uiCoreDataModel
import androidx.a2ui.engine.processor.A2uiCoreMessageProcessor
import androidx.a2ui.model.processor.A2uiMessageProcessor
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.a2ui.model.protocol.A2uiCreateSurfaceMessage
import androidx.a2ui.model.protocol.A2uiDataPath
import androidx.a2ui.model.protocol.A2uiException
import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage
import androidx.compose.runtime.Composable
import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.CardIrValidator
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.ir.ActionEvent
import io.github.yeyi.agent.genui.adapter.UiRenderAdapter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * A2UI 后端适配器：CardIr → A2UI v0.9.1 协议投影 + androidx.a2ui 官方引擎状态机。
 *
 * [RenderCard] 是「使用无感」的插槽入口：投影前先过 [CardIrValidator] 白名单校验，
 * 校验失败整卡降级为纯展示（无动作）；投影结果经 [A2uiCoreMessageProcessor] 落地为
 * 协议消息流（createSurface + updateComponents 扁平邻接表），由官方引擎维护 surface 状态。
 *
 * 动作回流：用户点击命中白名单的动作节点后，官方渲染器回调引擎 dispatchAction，
 * 引擎经 outboundEvents 发射 [A2uiClientEventMessage]，本适配器将其映射回 [ActionEvent]
 * 并转发给卡片对应的 onAction 回调，demo 侧与自研后端收口完全一致。
 *
 * 注：androidx.a2ui.compose 下的官方 Compose 渲染制品（A2uiSurface）在公开 Google Maven
 * 暂未发布，本适配器先交付投影 + 引擎状态机 + 回流映射的完整核心；渲染入口待官方制品
 * 发布后接入。程序化投递入口为 [project]。
 *
 * @param catalog 组件目录契约：投影时组件名必须映射到目录内组件，官方引擎按目录 schema 校验
 * @param scope 引擎消息循环与动作回流收集的运行域，调用方持有生命周期
 * @param validator 动作白名单校验器，与自研后端共用同一安全语义
 */
public class A2uiRenderAdapter(
    public val catalog: A2uiCoreCatalog,
    public val scope: CoroutineScope,
    private val validator: CardIrValidator = CardIrValidator(),
) : UiRenderAdapter {

    /** 官方引擎消息处理器：维护 surface 状态、schema 校验、增量更新与动作事件流。 */
    public val processor: A2uiMessageProcessor = A2uiCoreMessageProcessor(
        catalogs = listOf(catalog),
        dataModelFactory = { InMemoryDataModel() },
        componentRegistryFactory = { InMemoryComponentRegistry() },
        actionInterceptors = emptyList(),
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
     * 投影前过 [CardIrValidator]：白名单非法（动作 id 为空或重复）时整卡降级为纯展示；
     * 节点动作未命中白名单时该节点不携带 action 属性，渲染为静态内容。
     * 同一 [CardIr.instanceId] 只投影一次（引擎消息与节点映射幂等），重复调用仅更新动作回调。
     *
     * @param card 待渲染的卡片 IR
     * @param onAction 动作回流出口：用户点击命中白名单的动作节点时，映射为 [ActionEvent] 并经此回调
     */
    public fun project(card: CardIr, onAction: (ActionEvent) -> Unit) {
        val session = sessions.computeIfAbsent(card.instanceId) { CardSession(card.instanceId) }
        session.onAction = onAction
        if (!session.projected.compareAndSet(false, true)) return

        val whitelist = validator.validateActions(card.actions)
        val payloads = ArrayList<A2uiComponentPayload>()
        session.nodeActions.clear()
        val idGen = AtomicInteger(1)
        flatten(card.root, ROOT_COMPONENT_ID, whitelist, session, payloads, idGen)

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
    }

    private fun flatten(
        node: TreeNode,
        componentId: String,
        whitelist: Map<String, Action>?,
        session: CardSession,
        out: MutableList<A2uiComponentPayload>,
        idGen: AtomicInteger,
    ) {
        val properties = LinkedHashMap<String, Any?>()
        for ((key, value) in node.bindings) {
            properties[key] = value.toPlatformValue()
        }
        if (node.children.isNotEmpty()) {
            val childIds = node.children.map { "node-" + idGen.getAndIncrement() }
            properties[A2UI_KEY_CHILDREN] = childIds
            for (index in node.children.indices) {
                flatten(node.children[index], childIds[index], whitelist, session, out, idGen)
            }
        }
        val action = node.action?.let { whitelist?.get(it) }
        if (action != null) {
            properties[A2UI_KEY_ACTION] = mapOf(
                A2UI_KEY_EVENT to mapOf(
                    A2UI_KEY_NAME to action.id,
                    A2UI_KEY_CONTEXT to emptyMap<String, Any>(),
                ),
            )
            session.nodeActions[componentId] = NodeActionRef(action, JsonObject(node.bindings))
        }
        out.add(A2uiComponentPayload(componentId, node.component, properties))
    }

    private fun handleOutbound(message: Any) {
        if (message is A2uiClientEventMessage) {
            dispatchClientEvent(message)
        }
    }

    internal fun dispatchClientEvent(message: A2uiClientEventMessage) {
        val session = sessions[message.surfaceId] ?: return
        val ref = session.nodeActions[message.componentId] ?: return
        session.onAction?.invoke(ActionEvent(session.instanceId, ref.action, ref.payload))
    }

    private class CardSession(
        val instanceId: String,
    ) {
        val projected: AtomicBoolean = AtomicBoolean(false)
        val nodeActions: MutableMap<String, NodeActionRef> = ConcurrentHashMap()

        @Volatile
        var onAction: ((ActionEvent) -> Unit)? = null
    }

    private class NodeActionRef(
        val action: Action,
        val payload: JsonObject,
    )

    private class InMemoryDataModel : A2uiCoreDataModel {
        private val store: MutableMap<String, Any?> = mutableMapOf()
        override fun update(path: A2uiDataPath, value: Any?) {
            store[path.normalizedPath] = value
        }

        override fun get(path: A2uiDataPath): Any? = store[path.normalizedPath]

        override fun close() {
            store.clear()
        }
    }

    private class InMemoryComponentRegistry : A2uiCoreComponentRegistry {
        private val components: MutableMap<String, A2uiComponentPayload> = mutableMapOf()
        override fun update(components: List<A2uiComponentPayload>) {
            for (payload in components) {
                this.components[payload.id] = payload
            }
        }

        override fun reportError(id: String, exception: A2uiException) {
            // 引擎 schema 校验失败等异常：内存后端无渲染可上报，静默忽略，投影层不中断
        }

        override fun close() {
            components.clear()
        }
    }

    private fun JsonElement.toPlatformValue(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> content
        }

        is JsonObject -> mapValues { (_, value) -> value.toPlatformValue() }
        is JsonArray -> map { it.toPlatformValue() }
    }

    private companion object {
        /** A2UI v0.9.1 组件扁平邻接表：根组件 id 约定。 */
        const val ROOT_COMPONENT_ID = "root"
        const val A2UI_KEY_CHILDREN = "children"
        const val A2UI_KEY_ACTION = "action"
        const val A2UI_KEY_EVENT = "event"
        const val A2UI_KEY_NAME = "name"
        const val A2UI_KEY_CONTEXT = "context"
    }
}
