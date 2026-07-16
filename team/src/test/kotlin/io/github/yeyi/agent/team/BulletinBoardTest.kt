@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.llm.ChatMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BulletinBoardTest {

    @Test
    fun `publishEvent emits to publishEvents`() = runTest {
        val bb = BulletinBoard()
        val task = "test task"
        val assignment = TaskAssignment("id1", listOf(Selection.Tool("get_time")), task)

        val collected = mutableListOf<PublishEvent>()
        val job = launch { bb.publishEvents.toList(collected) }
        runCurrent()
        bb.publishEvent(assignment)
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        assertIs<TaskAssignment>(collected[0])
        assertEquals("id1", (collected[0] as TaskAssignment).taskId)
    }

    @Test
    fun `progressEvent emits to progressEvents`() = runTest {
        val bb = BulletinBoard()
        val update = TaskUpdate(
            "id1",
            AgentEvent.Final(AgentResult(ChatMessage.Assistant("ok"), 1, emptyList(), null))
        )

        val collected = mutableListOf<ProgressEvent>()
        val job = launch { bb.progressEvents.toList(collected) }
        runCurrent()
        bb.progressEvent(update)
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        assertIs<TaskUpdate>(collected[0])
    }

    @Test
    fun `publishEvent does NOT appear in progressEvents`() = runTest {
        val bb = BulletinBoard()
        val assignment = TaskAssignment("id1", emptyList(), "task")

        val collected = mutableListOf<ProgressEvent>()
        val job = launch { bb.progressEvents.toList(collected) }
        runCurrent()
        bb.publishEvent(assignment)
        runCurrent()
        job.cancel()

        assertTrue(collected.isEmpty())
    }

    @Test
    fun `progressEvent does NOT appear in publishEvents`() = runTest {
        val bb = BulletinBoard()
        val update = TaskUpdate(
            "id1",
            AgentEvent.Final(AgentResult(ChatMessage.Assistant("ok"), 1, emptyList(), null))
        )

        val collected = mutableListOf<PublishEvent>()
        val job = launch { bb.publishEvents.toList(collected) }
        runCurrent()
        bb.progressEvent(update)
        runCurrent()
        job.cancel()

        assertTrue(collected.isEmpty())
    }

    @Test
    fun `events global bus contains all events`() = runTest {
        val bb = BulletinBoard()
        val assignment = TaskAssignment("id1", emptyList(), "task")
        val update = TaskUpdate(
            "id1",
            AgentEvent.Final(AgentResult(ChatMessage.Assistant("ok"), 1, emptyList(), null))
        )

        val collected = mutableListOf<BulletinEvent>()
        val job = launch { bb.events.toList(collected) }
        runCurrent()
        bb.publishEvent(assignment)
        bb.progressEvent(update)
        runCurrent()
        job.cancel()

        assertEquals(2, collected.size)
    }
}
