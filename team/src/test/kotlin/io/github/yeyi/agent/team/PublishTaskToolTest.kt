@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublishTaskToolTest {

    private val emptyCaps: Map<String, List<NamedCapability>> = emptyMap()

    // 构造一个最小可用的 ToolContext（execute 不消费 agentContext，但 ToolContext.agentContext 非空）
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

    @Test
    fun `publish single task returns success`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "my_task")
                    put("selection", buildJsonObject {
                        put("type", "tool")
                        put("name", "echo")
                    })
                    put("task", "say hello")
                })
            }
        }

        val result = tool.execute(args, ctx())
        assertTrue(result.parts.text.contains("1 task(s) published"))
        assertTrue(result.parts.text.contains("tool(echo)"))
    }

    @Test
    fun `missing tasks array returns error`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject { }

        val result = tool.execute(args, ctx())
        assertTrue(result.isError)
        assertEquals("Missing 'tasks' array", result.parts.text)
    }

    @Test
    fun `missing ref field returns error`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("selection", buildJsonObject {
                        put("type", "tool")
                        put("name", "echo")
                    })
                    put("task", "hello")
                })
            }
        }

        val result = tool.execute(args, ctx())
        assertTrue(result.isError)
        assertTrue(result.parts.text.contains("Missing 'ref'"))
    }

    @Test
    fun `unknown selection type returns error`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "t1")
                    put("selection", buildJsonObject {
                            put("type", "unknown_type")
                            put("name", "foo")
                        })
                    put("task", "hello")
                })
            }
        }

        val result = tool.execute(args, ctx())
        assertTrue(result.isError)
        assertTrue(result.parts.text.contains("Unknown selection type"))
    }

    @Test
    fun `publish multiple tasks returns multi-line summary`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "t1")
                    put("selection", buildJsonObject {
                        put("type", "tool")
                        put("name", "echo")
                    })
                    put("task", "task1")
                })
                add(buildJsonObject {
                    put("ref", "t2")
                    put("selection", buildJsonObject {
                            put("type", "tool")
                            put("name", "calc")
                        })
                    put("task", "task2")
                })
            }
        }

        val result = tool.execute(args, ctx())
        assertTrue(result.parts.text.contains("2 task(s) published"))
    }

    @Test
    fun `published event can be received via BulletinBoard`() = runTest {
        val bb = BulletinBoard()
        val tool = PublishTaskTool(bb, emptyCaps)
        val args = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("ref", "my_task")
                    put("selection", buildJsonObject {
                        put("type", "tool")
                        put("name", "echo")
                    })
                    put("task", "hello")
                })
            }
        }

        val collected = mutableListOf<BulletinEvent>()
        val job = launch { bb.events.collect { collected.add(it) } }
        runCurrent()  // 确保 collector 已订阅

        tool.execute(args, ctx())
        runCurrent()  // 驱动 emit 与 collect 之间的协作

        job.cancel()
        runCurrent()

        assertEquals(1, collected.size)
        assertTrue(collected[0] is TaskAssignments)
    }

    @Test
    fun `parametersSchema enum contains all selection types`() {
        val caps = mapOf(
            "skill" to listOf(NamedCapability("s1", "d1")),
            "tool" to listOf(NamedCapability("t1", "d1")),
            "toolset" to listOf(NamedCapability("ts1", "d1")),
            "subagent" to listOf(NamedCapability("sa1", "d1")),
        )
        val tool = PublishTaskTool(BulletinBoard(), caps)
        val schema = (tool.parametersSchema as ToolParameters.JsonSchema).schema
        assertTrue(schema.contains("\"skill\""), "schema should contain skill enum value")
        assertTrue(schema.contains("\"tool\""), "schema should contain tool enum value")
        assertTrue(schema.contains("\"toolset\""), "schema should contain toolset enum value")
        assertTrue(schema.contains("\"subagent\""), "schema should contain subagent enum value")
    }
}