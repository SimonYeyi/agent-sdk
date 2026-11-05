package io.github.yeyi.agent.genui.adapter.a2ui

import androidx.a2ui.engine.catalog.A2uiCoreCatalog
import androidx.a2ui.engine.catalog.A2uiCoreComponentDefinition
import androidx.a2ui.engine.catalog.A2uiCoreComponentDefinitionCollection
import androidx.a2ui.model.catalog.A2uiFunctionCollection
import androidx.a2ui.model.protocol.A2uiClientDataModel
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.schema.A2uiObjectSchema
import androidx.a2ui.model.schema.A2uiSchema
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
 * 引擎层纯 JVM 状态机可独立验证：投影（createSurface + updateComponents）落地后
 * activeSurfaces 出现对应 surface；动作回流不依赖官方渲染器，直接以客户端事件消息
 * 驱动 [A2uiRenderAdapter.dispatchClientEvent] 验证 UiActionEvent 映射。
 */
class A2uiRenderAdapterTest {

    @Test
    fun projectCreatesSurfaceAndMapsActionReflow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = A2uiRenderAdapter(TestCatalog(), scope)
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
                                action = "choose",
                            ),
                        ),
                    ),
                    actions = listOf(Action(id = "choose", label = "选择")),
                ),
            ) { events += it }

            withTimeout(5_000) { adapter.processor.activeSurfaces.first { it.isNotEmpty() } }
            assertEquals("card-1", adapter.processor.activeSurfaces.value.single().id)

            // 回流：Button 子节点按先序编号为 node-2（Text=node-1），命中白名单动作，映射回 ActionEvent
            adapter.dispatchClientEvent(eventMessage("card-1", "node-2"))
            assertEquals(1, events.size)
            assertEquals("card-1", events[0].instanceId)
            assertEquals("choose", events[0].action.id)
            assertEquals(JsonPrimitive("r1"), events[0].payload["route"])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun nodeActionOutsideWhitelistIsNotBound() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = A2uiRenderAdapter(TestCatalog(), scope)
        val events = mutableListOf<ActionEvent>()
        try {
            adapter.project(
                CardIr(
                    instanceId = "card-2",
                    root = TreeNode(
                        component = "Button",
                        bindings = mapOf("route" to JsonPrimitive("r2")),
                        action = "unknown", // 不在白名单
                    ),
                    actions = listOf(Action(id = "choose", label = "选择")),
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
    fun invalidWhitelistDegradesWholeCardToDisplayOnly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = A2uiRenderAdapter(TestCatalog(), scope)
        val events = mutableListOf<ActionEvent>()
        try {
            adapter.project(
                CardIr(
                    instanceId = "card-3",
                    root = TreeNode(
                        component = "Button",
                        bindings = mapOf("route" to JsonPrimitive("r3")),
                        action = "choose",
                    ),
                    actions = listOf(Action(id = "choose", label = "选择"), Action(id = "choose", label = "重复")),
                ),
            ) { events += it }

            withTimeout(5_000) { adapter.processor.activeSurfaces.first { it.isNotEmpty() } }

            // 白名单非法（重复 id）→ 整卡降级为纯展示，无动作可回流
            adapter.dispatchClientEvent(eventMessage("card-3", "root"))
            assertTrue(events.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repeatedProjectIsIdempotentAndUpdatesCallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = A2uiRenderAdapter(TestCatalog(), scope)
        val events = mutableListOf<ActionEvent>()
        val replaced = mutableListOf<ActionEvent>()
        try {
            val card = CardIr(
                instanceId = "card-4",
                root = TreeNode(component = "Button", action = "choose"),
                actions = listOf(Action(id = "choose", label = "选择")),
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

    private class TestCatalog : A2uiCoreCatalog {
        private val components = listOf(
            TestComponent("Column"),
            TestComponent("Text"),
            TestComponent("Button"),
        )

        override val id: String = "test-catalog"
        override val componentDefinitions: A2uiCoreComponentDefinitionCollection =
            A2uiCoreComponentDefinitionCollection(components)
        override val functions: A2uiFunctionCollection =
            A2uiFunctionCollection(emptyList())
    }

    private class TestComponent(
        override val name: String,
        override val propertySchema: A2uiSchema = A2uiObjectSchema(),
        override val description: String = "",
    ) : A2uiCoreComponentDefinition
}
