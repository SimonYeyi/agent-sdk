package io.github.yeyi.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [TurnFlow] 的事件出口契约。
 *
 * 出口在收集时装一次 —— 未装即发射是内部不变式违规，明确抛 [IllegalStateException]，
 * 不静默丢弃（静默吞事件会表现为"事件凭空消失"，是最难查的一类故障）。
 */
class TurnFlowTest {

    @Test
    fun `emitting before the flow is started throws IllegalStateException`() = runTest {
        val flow = TurnFlow()

        val ex = assertFailsWith<IllegalStateException> {
            flow.emit(AgentEvent.Initial(AgentQuery.text("hi")))
        }
        assertTrue(ex.message!!.contains("turn flow is not started yet"))
    }
}
