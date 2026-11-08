package io.github.yeyi.agent.genui.adapter.default

import io.github.yeyi.agent.genui.ir.Action
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 组件动作出口：动作由「拥有者」声明，节点自带与组件声明两条路径都能发起回流。
 */
class UiActionScopeTest {

    private class Emitted(val action: Action, val payload: JsonObject)

    private fun scope(
        nodeAction: Action? = null,
        declared: Map<String, Action> = emptyMap(),
        payload: JsonObject = JsonObject(emptyMap()),
        sink: MutableList<Emitted>,
    ): UiActionScope = UiActionScope(
        action = nodeAction,
        declared = declared,
        payload = payload,
        onAction = { action, payload -> sink += Emitted(action, payload) },
    )

    @Test
    fun `emit 命中节点自带动作`() {
        val sink = mutableListOf<Emitted>()
        val scope = scope(
            nodeAction = Action("navigate", "开启导航"),
            payload = JsonObject(mapOf("city" to JsonPrimitive("珠海"))),
            sink = sink,
        )

        scope.emit("navigate")

        assertEquals(1, sink.size)
        assertEquals("navigate", sink[0].action.id)
        assertEquals("开启导航", sink[0].action.label)
        // 载荷缺省取节点绑定数据：工具传给组件的业务数据随动作一起回流
        assertEquals("珠海", sink[0].payload["city"]?.toString()?.trim('"'))
    }

    @Test
    fun `emit 命中组件声明动作`() {
        val sink = mutableListOf<Emitted>()
        val scope = scope(
            declared = mapOf("navigate" to Action("navigate", "开启导航")),
            payload = JsonObject(mapOf("city" to JsonPrimitive("珠海"))),
            sink = sink,
        )

        scope.emit("navigate")

        assertEquals(1, sink.size)
        assertEquals("开启导航", sink[0].action.label)
        assertEquals("珠海", sink[0].payload["city"]?.toString()?.trim('"'))
    }

    @Test
    fun `emit 未命中任何声明时不发出`() {
        val sink = mutableListOf<Emitted>()
        val scope = scope(
            nodeAction = Action("navigate", "开启导航"),
            declared = mapOf("refresh" to Action("refresh", "刷新")),
            sink = sink,
        )

        scope.emit("unknown")

        assertTrue(sink.isEmpty())
    }

    @Test
    fun `emit 可显式指定载荷`() {
        val sink = mutableListOf<Emitted>()
        val scope = scope(nodeAction = Action("navigate", "开启导航"), sink = sink)

        scope.emit("navigate", JsonObject(mapOf("id" to JsonPrimitive("42"))))

        assertEquals(1, sink.size)
        assertEquals("42", sink[0].payload["id"]?.toString()?.trim('"'))
    }
}
