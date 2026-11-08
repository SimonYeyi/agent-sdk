package io.github.yeyi.agent.genui.adapter.a2ui

import androidx.a2ui.model.catalog.functions.A2uiLocaleProvider
import androidx.a2ui.model.protocol.A2uiClientDataModel
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
                            TreeNode(component = "Text", bindings = mapOf("text" to JsonPrimitive("hello"))),
                            TreeNode(
                                component = "Button",
                                bindings = mapOf("route" to JsonPrimitive("r1")),
                                action = Action(id = "choose", label = "选择"),
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
            assertEquals("choose", events[0].action.id)
            assertEquals("选择", events[0].action.label)
            assertEquals(JsonPrimitive("r1"), events[0].payload["route"])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun blankActionIdDegradesWholeCardToDisplayOnly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = A2uiRenderAdapter(catalog, scope)
        val events = mutableListOf<ActionEvent>()
        try {
            adapter.project(
                CardIr(
                    instanceId = "card-2",
                    root = TreeNode(
                        component = "Button",
                        bindings = mapOf("route" to JsonPrimitive("r2")),
                        action = Action(id = " ", label = "选择"), // id 为空：卡内动作非法
                    ),
                ),
            ) { events += it }

            withTimeout(5_000) { adapter.processor.activeSurfaces.first { it.isNotEmpty() } }

            adapter.dispatchClientEvent(eventMessage("card-2", "root"))
            assertTrue(events.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun conflictingActionLabelsDegradeWholeCardToDisplayOnly() = runBlocking {
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
                                bindings = mapOf("route" to JsonPrimitive("r3")),
                                action = Action(id = "choose", label = "选择"),
                            ),
                            // 同 id 不同 label：动作语义歧义，卡内动作非法
                            TreeNode(
                                component = "Button",
                                bindings = mapOf("route" to JsonPrimitive("r4")),
                                action = Action(id = "choose", label = "重复"),
                            ),
                        ),
                    ),
                ),
            ) { events += it }

            withTimeout(5_000) { adapter.processor.activeSurfaces.first { it.isNotEmpty() } }

            // 卡内动作非法 → 整卡降级为纯展示，无动作可回流
            adapter.dispatchClientEvent(eventMessage("card-3", "node-1"))
            assertTrue(events.isEmpty())
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
                root = TreeNode(component = "Button", action = Action(id = "choose", label = "选择")),
            )
            adapter.project(card) { events += it }
            adapter.project(card) { replaced += it } // 重复投影：仅更新回调，不重复投递

            adapter.dispatchClientEvent(eventMessage("card-4", "root"))
            assertTrue(events.isEmpty())
            assertEquals(1, replaced.size)
            assertEquals("choose", replaced[0].action.id)
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
