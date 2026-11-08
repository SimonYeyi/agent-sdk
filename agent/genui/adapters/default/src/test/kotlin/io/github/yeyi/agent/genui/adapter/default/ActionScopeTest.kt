package io.github.yeyi.agent.genui.adapter.default

import io.github.yeyi.agent.genui.ir.Action
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 组件动作出口：动作值由调用方给出（节点自带或模板自有），出口只负责回流。
 */
class ActionScopeTest {

    private class Emitted(val action: Action, val payload: JsonObject?)

    private fun scope(sink: MutableList<Emitted>): ActionScope =
        ActionScope { action, payload -> sink += Emitted(action, payload) }

    @Test
    fun `emit 以动作值回流 载荷缺省交由适配器填充`() {
        val sink = mutableListOf<Emitted>()
        val scope = scope(sink)

        scope.emit(Action("navigate", "开启导航"))

        assertEquals(1, sink.size)
        assertEquals("navigate", sink[0].action.id)
        assertEquals("开启导航", sink[0].action.label)
        // 缺省载荷由适配器按节点绑定数据填充，出口只透传
        assertNull(sink[0].payload)
    }

    @Test
    fun `emit 可显式指定载荷`() {
        val sink = mutableListOf<Emitted>()
        val scope = scope(sink)

        scope.emit(Action("navigate", "开启导航"), JsonObject(mapOf("id" to JsonPrimitive("42"))))

        assertEquals(1, sink.size)
        assertEquals("42", sink[0].payload?.get("id")?.toString()?.trim('"'))
    }
}
