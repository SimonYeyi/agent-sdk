package io.github.yeyi.agent.genui.adapter.a2ui

import androidx.a2ui.model.catalog.functions.A2uiLocaleProvider
import androidx.a2ui.model.protocol.A2uiClientDataModel
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.ir.ActionEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * A2uiRenderAdapter 投影与回流映射测试。
 *
 * 使用官方 Material A2uiBasicCatalogV1 目录（material3-a2ui）驱动引擎状态机，
 * 纯 JVM 可独立验证：投影（createSurface + updateComponents）落地后 activeSurfaces
 * 出现对应 surface；动作回流不依赖 Compose 渲染，直接以客户端事件消息驱动
 * [A2uiRenderAdapter.dispatchClientEvent] 验证 UiActionEvent 映射。
 */
class A2uiRenderAdapterTest {

    // 官方 Material 目录工厂：image/video/audioPlayer 使用无渲染器空实现（测试不触发 Compose
    // 渲染，仅驱动引擎状态机），urlOpener/messageFormatter 用空实现，localeProvider 用官方默认
    private val catalog =
        materialA2uiBasicCatalogV1(
            image = MaterialA2uiBasicCatalogV1Defaults.image { _, _, _, _, _ -> },
            video = MaterialA2uiBasicCatalogV1Defaults.video { _, _, _ -> },
            audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer { _, _, _, _ -> },
            urlOpener = {},
            messageFormatter = { pattern, _, _ -> pattern },
            localeProvider = A2uiLocaleProvider.Default,
        )

    @Test
    fun projectCreatesSurfaceAndMapsActionReflow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = A2uiRenderAdapter(catalog, scope)
        val events = mutableListOf<ActionEvent>()
        try {
            adapter.project(
                CardIr(
                    instanceId = "card-1",
                    root = TreeNode(
                        component = "Column",
                        children = listOf(
                            TreeNode(component = "Text", bindings = mapOf("content" to JsonPrimitive("hello"))),
                            TreeNode(
                                component = "Button",
                                action = Action("选择", payload = "r1"),
                            ),
                        ),
                    ),
                ),
            ) { events += it }

            withTimeout(5_000) { adapter.processor.activeSurfaces.first { it.isNotEmpty() } }
            assertEquals("card-1", adapter.processor.activeSurfaces.value.single().id)

            // 回流：Button 子节点按先序编号为 node-2（Text=node-1），命中节点自带动作，映射回 ActionEvent
            adapter.dispatchClientEvent(eventMessage("card-1", "node-2"))
            assertEquals(1, events.size)
            assertEquals("card-1", events[0].instanceId)
            assertEquals("选择", events[0].action.label)
            assertEquals("r1", events[0].action.payload)
            assertEquals("选择（r1）", events[0].action.reflowable)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun blankRootActionProjectsNoSurface() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = A2uiRenderAdapter(catalog, scope)
        val events = mutableListOf<ActionEvent>()
        try {
            adapter.project(
                CardIr(
                    instanceId = "card-2",
                    root = TreeNode(
                        component = "Button",
                        action = Action(" "), // 文案为空：整棵不渲染
                    ),
                ),
            ) { events += it }

            // root 不可渲染：不建 surface（空 surface 会滞留在引擎状态机里）
            val appeared = withTimeoutOrNull(500) {
                adapter.processor.activeSurfaces.first { it.isNotEmpty() }
            }
            assertEquals(null, appeared)

            // 无 surface 也无动作可回流
            adapter.dispatchClientEvent(eventMessage("card-2", "root"))
            assertTrue(events.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun blankActionLabelSkipsOnlyThatNode() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = A2uiRenderAdapter(catalog, scope)
        val events = mutableListOf<ActionEvent>()
        try {
            adapter.project(
                CardIr(
                    instanceId = "card-5",
                    root = TreeNode(
                        component = "Column",
                        children = listOf(
                            TreeNode(component = "Button", action = Action(" ")), // 空文案：不投影
                            TreeNode(component = "Button", action = Action("选择", payload = "r5")),
                        ),
                    ),
                ),
            ) { events += it }

            withTimeout(5_000) { adapter.processor.activeSurfaces.first { it.isNotEmpty() } }

            // id 只给可渲染的子节点分配：可渲染的那个是 node-1
            adapter.dispatchClientEvent(eventMessage("card-5", "node-1"))
            assertEquals(1, events.size)
            assertEquals("选择", events[0].action.label)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun duplicatedActionLabelsAreLegalAndBothReflow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = A2uiRenderAdapter(catalog, scope)
        val events = mutableListOf<ActionEvent>()
        try {
            adapter.project(
                CardIr(
                    instanceId = "card-3",
                    root = TreeNode(
                        component = "Column",
                        children = listOf(
                            TreeNode(
                                component = "Button",
                                action = Action("选择", payload = "r3"),
                            ),
                            // 文案相同：动作唯一性由作者负责，框架不判非法
                            TreeNode(
                                component = "Button",
                                action = Action("选择", payload = "r4"),
                            ),
                        ),
                    ),
                ),
            ) { events += it }

            withTimeout(5_000) { adapter.processor.activeSurfaces.first { it.isNotEmpty() } }

            adapter.dispatchClientEvent(eventMessage("card-3", "node-1"))
            adapter.dispatchClientEvent(eventMessage("card-3", "node-2"))
            assertEquals(2, events.size)
            assertEquals("r3", events[0].action.payload)
            assertEquals("r4", events[1].action.payload)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repeatedProjectIsIdempotentAndUpdatesCallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = A2uiRenderAdapter(catalog, scope)
        val events = mutableListOf<ActionEvent>()
        val replaced = mutableListOf<ActionEvent>()
        try {
            val card = CardIr(
                instanceId = "card-4",
                root = TreeNode(component = "Button", action = Action("选择")),
            )
            adapter.project(card) { events += it }
            adapter.project(card) { replaced += it } // 重复投影：仅更新回调，不重复投递

            adapter.dispatchClientEvent(eventMessage("card-4", "root"))
            assertTrue(events.isEmpty())
            assertEquals(1, replaced.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun projectionSatisfiesOfficialCatalogContract() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = A2uiRenderAdapter(catalog, scope)
        val errors = mutableListOf<A2uiClientErrorMessage>()
        scope.launch {
            adapter.processor.outboundEvents.collect { if (it is A2uiClientErrorMessage) errors += it }
        }
        try {
            adapter.project(
                CardIr(
                    instanceId = "card-6",
                    root = TreeNode(
                        component = "Card",
                        children = listOf(
                            TreeNode(
                                component = "Text",
                                bindings = mapOf("content" to JsonPrimitive("北京 晴 26°C")),
                            ),
                            TreeNode(component = "Divider"),
                            TreeNode(
                                component = "Column",
                                bindings = mapOf("arrangement" to JsonPrimitive("spaceBetween")),
                                children = listOf(
                                    TreeNode(
                                        component = "Text",
                                        bindings = mapOf("content" to JsonPrimitive("26°")),
                                    ),
                                    TreeNode(component = "Button", action = Action("查看明天", payload = "北京")),
                                ),
                            ),
                        ),
                    ),
                ),
            ) {}

            withTimeout(5_000) { adapter.processor.activeSurfaces.first { it.isNotEmpty() } }
            delay(300)

            // 官方目录对每个组件有必填属性，缺项的组件会被引擎校验丢弃
            assertTrue(errors.isEmpty(), "官方目录校验失败：$errors")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun buttonWithoutActionProjectsAsStaticText() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = A2uiRenderAdapter(catalog, scope)
        val errors = mutableListOf<A2uiClientErrorMessage>()
        scope.launch {
            adapter.processor.outboundEvents.collect { if (it is A2uiClientErrorMessage) errors += it }
        }
        try {
            adapter.project(
                CardIr(
                    instanceId = "card-7",
                    root = TreeNode(
                        component = "Button",
                        bindings = mapOf("label" to JsonPrimitive("暂不可用")),
                    ),
                ),
            ) {}

            withTimeout(5_000) { adapter.processor.activeSurfaces.first { it.isNotEmpty() } }
            delay(300)

            // 官方 Button 的 action 必填：无动作的按钮按静态文本投影，不该报校验错
            assertTrue(errors.isEmpty(), "官方目录校验失败：$errors")
        } finally {
            scope.cancel()
        }
    }

    private fun eventMessage(surfaceId: String, componentId: String): A2uiClientEventMessage =
        A2uiClientEventMessage(
            type = "event",
            surfaceId = surfaceId,
            componentId = componentId,
            timestamp = System.currentTimeMillis(),
            context = emptyMap(),
            clientDataModel = A2uiClientDataModel(emptyMap()),
        )
}
