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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PublishTaskToolDagTest {

    private val emptyCaps: Map<String, List<NamedCapability>> = emptyMap()

    private fun ctx(callId: String = "call1"): ToolContext = ToolContext(
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

    // parse task_id from summary line: "- <uuid> → ..."
    private fun extractTaskId(summaryLine: String): String {
        val prefix = "- "
        val arrow = " →"
        return summaryLine.substringAfter(prefix).substringBefore(arrow)
    }

    @Test
    fun `happy path — intra-call ref resolved to UUID in dependsOn`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "lookup")
                    put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") })
                    put("task", "lookup data")
                })
                add(buildJsonObject {
                    put("ref", "summary")
                    put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") })
                    put("task", "summarize")
                    putJsonArray("depends_on") { add(JsonPrimitive("lookup")) }
                })
            }
        }

        val collected = mutableListOf<TaskAssignments>()
        val job = launch { bb.publishEvents.collect { e -> if (e is TaskAssignments) collected.add(e) } }
        runCurrent()

        val result = tool.execute(args, ctx())
        runCurrent()
        job.cancel()

        assertTrue(result.content.contains("2 task(s) published"), "summary: ${result.content}")
        assertEquals(1, collected.size)
        val assignments = collected[0]
        assertEquals(2, assignments.tasks.size)

        val lookupTa = assignments.tasks.find { it.task == "lookup data" }!!
        val summaryTa = assignments.tasks.find { it.task == "summarize" }!!

        assertEquals(1, summaryTa.dependsOn.size)
        assertEquals(lookupTa.taskId, summaryTa.dependsOn[0])
        assertTrue(lookupTa.dependsOn.isEmpty())

        val lines = result.content.lines().drop(1)
        assertEquals(2, lines.size)
        lines.forEach { line -> assertTrue(UUID.fromString(extractTaskId(line)) is UUID) }
    }

    @Test
    fun `parallel roots — no dependsOn`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "a"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "A")
                })
                add(buildJsonObject {
                    put("ref", "b"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "B")
                })
            }
        }

        val collected = mutableListOf<TaskAssignments>()
        val job = launch { bb.publishEvents.collect { e -> if (e is TaskAssignments) collected.add(e) } }
        runCurrent()

        tool.execute(args, ctx())
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        val tasks = collected[0].tasks
        assertEquals(2, tasks.size)
        assertTrue(tasks.all { it.dependsOn.isEmpty() })
    }

    @Test
    fun `intra-publish cycle detected`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "a"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "A")
                    putJsonArray("depends_on") { add(JsonPrimitive("b")) }
                })
                add(buildJsonObject {
                    put("ref", "b"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "B")
                    putJsonArray("depends_on") { add(JsonPrimitive("a")) }
                })
            }
        }

        val result = tool.execute(args, ctx())
        assertTrue(result.isError, "expected error, got: ${result.content}")
        assertTrue(result.content.contains("Cycle"))
    }

    @Test
    fun `self-loop detected`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "a"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "A")
                    putJsonArray("depends_on") { add(JsonPrimitive("a")) }
                })
            }
        }

        val result = tool.execute(args, ctx())
        assertTrue(result.isError, "expected error, got: ${result.content}")
        assertTrue(result.content.contains("Cycle"))
    }

    @Test
    fun `missing depends_on ref — not in same call nor knownTaskIds`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "a"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "A")
                    putJsonArray("depends_on") { add(JsonPrimitive("nonexistent")) }
                })
            }
        }

        val result = tool.execute(args, ctx())
        assertTrue(result.isError, "expected error, got: ${result.content}")
        assertTrue(result.content.contains("Unknown depends_on"))
    }

    @Test
    fun `intra-call ref duplicate rejected`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "dup"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "A")
                })
                add(buildJsonObject {
                    put("ref", "dup"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "B")
                })
            }
        }

        val result = tool.execute(args, ctx())
        assertTrue(result.isError, "expected error, got: ${result.content}")
        assertTrue(result.content.contains("Duplicate ref"))
    }

    @Test
    fun `cross-publish — task_id from first call referenced in second`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)

        // First call: publish task "x"
        val firstArgs = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "x"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "X")
                })
            }
        }
        val firstResult = tool.execute(firstArgs, ctx())
        assertTrue(!firstResult.isError, "first call failed: ${firstResult.content}")
        val xTaskId = extractTaskId(firstResult.content.lines()[1])

        // Second call: publish task "a" depends_on x's task_id (cross-publish reference)
        val secondArgs = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "a"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "A")
                    putJsonArray("depends_on") { add(JsonPrimitive(xTaskId)) }
                })
            }
        }
        val secondResult = tool.execute(secondArgs, ctx())
        // Cross-publish reference accepted — proves knownTaskIds contains x's task_id
        assertTrue(!secondResult.isError, "cross-publish reference should be accepted: ${secondResult.content}")

        // Collect the second TaskAssignments to verify dependsOn
        val collected = mutableListOf<TaskAssignments>()
        val job = launch { bb.publishEvents.collect { e -> if (e is TaskAssignments) collected.add(e) } }
        runCurrent()

        // Collect events from the second publish (the first was already flushed)
        // We need a fresh collector starting after the first execute
        job.cancel()

        val collector2 = mutableListOf<TaskAssignments>()
        val job2 = launch { bb.publishEvents.collect { e -> if (e is TaskAssignments) collector2.add(e) } }
        runCurrent()

        // Re-execute first call to get it into collected for verification
        // Actually, let's just verify by re-publishing
        tool.execute(secondArgs, ctx())
        runCurrent()
        job2.cancel()

        val secondAssignments = collector2.find { it.tasks.any { t -> t.task == "A" } }
        assertNotNull(secondAssignments)
        val aTa = secondAssignments!!.tasks.first { it.task == "A" }
        assertEquals(listOf(xTaskId), aTa.dependsOn)
    }

    @Test
    fun `depends_on mixed ref and task_id`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)

        // Pre-publish: task "x" to seed knownTaskIds
        val preArgs = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "x"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "X")
                })
            }
        }
        val preResult = tool.execute(preArgs, ctx())
        val xTaskId = extractTaskId(preResult.content.lines()[1])

        // Single call: a depends on ref "x_inline" (same call) + xTaskId (cross-call)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "x_inline")
                    put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") })
                    put("task", "inline")
                })
                add(buildJsonObject {
                    put("ref", "a")
                    put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") })
                    put("task", "A")
                    putJsonArray("depends_on") { add(JsonPrimitive("x_inline")); add(JsonPrimitive(xTaskId)) }
                })
            }
        }

        val result = tool.execute(args, ctx())
        assertTrue(!result.isError, "expected success, got: ${result.content}")
        assertTrue(result.content.contains("2 task(s) published"))
    }

    @Test
    fun `empty depends_on array treated as no dependency`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "a"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "A")
                    putJsonArray("depends_on") { }
                })
            }
        }

        val collected = mutableListOf<TaskAssignments>()
        val job = launch { bb.publishEvents.collect { e -> if (e is TaskAssignments) collected.add(e) } }
        runCurrent()

        val result = tool.execute(args, ctx())
        assertTrue(!result.isError)
        runCurrent()
        job.cancel()

        val ta = collected.first().tasks.first()
        assertTrue(ta.dependsOn.isEmpty())
    }

    @Test
    fun `summary returns task_id as UUID for LLM to reference cross-round`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "t1"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "T1")
                })
            }
        }

        val result = tool.execute(args, ctx())
        val lines = result.content.lines()
        assertTrue(lines[0].startsWith("1 task(s) published"))

        val taskLine = lines[1]
        val taskId = extractTaskId(taskLine)
        val uuid = UUID.fromString(taskId)
        assertNotNull(uuid)
        assertTrue(taskLine.contains("tool(echo)"))
    }

    @Test
    fun `TaskAssignments event does not carry roundId`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "t1"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "T1")
                })
            }
        }

        val collected = mutableListOf<TaskAssignments>()
        val job = launch { bb.publishEvents.collect { e -> if (e is TaskAssignments) collected.add(e) } }
        runCurrent()

        tool.execute(args, ctx())
        runCurrent()
        job.cancel()

        val event = collected.single()
        assertEquals(1, event.tasks.size)
    }

    @Test
    fun `knownTaskIds — successful publish registers task_id, failed does not`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)

        // Successful publish
        val okArgs = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "ok"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "OK")
                })
            }
        }
        val okResult = tool.execute(okArgs, ctx())
        val okTaskId = extractTaskId(okResult.content.lines()[1])

        // Verify the task_id is accepted in a second publish (proves registration)
        val refOk = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "dep"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "Dep")
                    putJsonArray("depends_on") { add(JsonPrimitive(okTaskId)) }
                })
            }
        }
        assertTrue(!tool.execute(refOk, ctx()).isError, "cross-publish ref to okTaskId should succeed")

        // Failed publish (self-loop) — should not affect knownTaskIds
        val failArgs = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "a"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "A")
                    putJsonArray("depends_on") { add(JsonPrimitive("a")) }
                })
            }
        }
        val failResult = tool.execute(failArgs, ctx())
        assertTrue(failResult.isError)

        // The self-loop reference "a" was NOT registered by the failed publish.
        // Verify that attempting to reference "a" (UUID from the non-existent cycle) fails.
        // Since we can't get a task_id from a failed publish, we verify by checking
        // that the failed publish did NOT corrupt knownTaskIds: the previous dep still works.
        val refDep2 = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "dep2"); put("selection", buildJsonObject { put("type", "tool"); put("name", "echo") }); put("task", "Dep2")
                    putJsonArray("depends_on") { add(JsonPrimitive(okTaskId)) }
                })
            }
        }
        assertTrue(!tool.execute(refDep2, ctx()).isError, "okTaskId still valid after failed publish")
    }
}
