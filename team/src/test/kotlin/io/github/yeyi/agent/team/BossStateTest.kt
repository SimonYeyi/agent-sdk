package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.llm.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossStateTest {

    @Test
    fun `TaskState terminal is true after Final`() {
        val state = TaskState(listOf(), "task")
        assertFalse(state.terminal)
        state.events.add(AgentEvent.Final(AgentResult(ChatMessage.Assistant("ok"), 1, emptyList(), null)))
        assertTrue(state.terminal)
    }

    @Test
    fun `TaskState terminal is true after Failed`() {
        val state = TaskState(listOf(), "task")
        assertFalse(state.terminal)
        state.events.add(AgentEvent.Failed(RuntimeException("err")))
        assertTrue(state.terminal)
    }

    @Test
    fun `BossState values are correct`() {
        assertEquals(4, BossState.entries.size)
        assertTrue(BossState.entries.containsAll(listOf(
            BossState.WAITING, BossState.RUNNING,
            BossState.INPUTTING, BossState.COLLECTING,
        )))
    }
}