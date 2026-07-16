@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.ToolContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CancelTaskToolTest {

    // 构造一个最小可用的 ToolContext（execute 不消费 agentContext，但 ToolContext.agentContext 非空）
    private fun ctx(callId: String): ToolContext = ToolContext(
        toolCallId = callId,
        agentContext = AgentContext(
            persona = Persona(""),
            maxIterations = 1,
            currentIteration = 1,
            memory = InMemoryMemory(),
            llmProvider = FakeLlmProvider(),
            tools = emptyList(),
            maxRounds = 20,
        ),
    )

    @Test
    fun `cancel emits Cancellation event`() = runTest {
        val bb = BulletinBoard()
        val tool = CancelTaskTool(bb)
        val args = buildJsonObject { put("task_id", "task-abc") }

        val collected = mutableListOf<PublishEvent>()
        val job = launch { bb.publishEvents.collect { collected.add(it) } }
        runCurrent()  // 确保 collector 已订阅

        val result = tool.execute(args, ctx("call1"))
        runCurrent()  // 驱动 emit

        job.cancel()
        runCurrent()

        assertTrue(result.content.contains("task-abc"))
        assertEquals(1, collected.size)
        assertTrue(collected[0] is Cancellation)
        assertEquals("task-abc", (collected[0] as Cancellation).taskId)
    }

    @Test
    fun `cancel with missing task_id returns error`() = runTest {
        val bb = BulletinBoard()
        val tool = CancelTaskTool(bb)
        val args = buildJsonObject { /* 空 */ }

        val result = tool.execute(args, ctx("call1"))

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing 'task_id'"))
    }

    @Test
    fun `cancel is idempotent — multiple cancels for same task_id are safe`() = runTest {
        val bb = BulletinBoard()
        val tool = CancelTaskTool(bb)
        val args = buildJsonObject { put("task_id", "task-xyz") }

        // collector 必须在 repeat 之前建立——SharedFlow.replay=0, 后续事件不会被回放.
        val events = mutableListOf<PublishEvent>()
        val job = launch { bb.publishEvents.collect { events.add(it) } }
        runCurrent()

        // 同一 taskId 多次取消 — 每次都发 Cancellation 事件, 幂等由下游 Pasture 静默处理.
        repeat(3) {
            val r = tool.execute(args, ctx("call$it"))
            assertTrue(r.content.contains("task-xyz"))
            runCurrent()
        }

        job.cancel()
        runCurrent()
        assertEquals(3, events.size)
    }
}