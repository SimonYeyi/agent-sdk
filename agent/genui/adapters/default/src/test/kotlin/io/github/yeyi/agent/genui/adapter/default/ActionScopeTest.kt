package io.github.yeyi.agent.genui.adapter.default

import io.github.yeyi.agent.genui.ir.Action
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 组件动作出口：动作值由调用方给出（节点自带或模板自有），出口只负责回流。
 */
class ActionScopeTest {

    private class Emitted(val action: Action)

    private fun scope(sink: MutableList<Emitted>): ActionScope =
        ActionScope { action -> sink += Emitted(action) }

    @Test
    fun `emit 以动作值回流`() {
        val sink = mutableListOf<Emitted>()
        val scope = scope(sink)

        scope.emit(Action("开启导航", payload = "北京"))

        assertEquals(1, sink.size)
        assertEquals("开启导航", sink[0].action.label)
        assertEquals("北京", sink[0].action.payload)
        assertEquals("开启导航（北京）", sink[0].action.reflowable)
    }
}
