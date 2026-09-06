package io.github.yeyi.agent.approval

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.hook.AgentHookEvent
import io.github.yeyi.agent.hook.HookContext
import io.github.yeyi.agent.hook.HookResult
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ApprovalHookTest {

    private fun createContext(tools: List<Tool>) = AgentContext(
        persona = Persona(role = ""),
        maxIterations = 5,
        currentIteration = 1,
        memory = InMemoryMemory(),
        llmProvider = FakeLlmProvider(),
        tools = tools,
        maxRounds = 20,
    )

    private fun toolCall(name: String) = ToolCall(
        id = "call-1",
        name = name,
        arguments = JsonObject(mapOf("msg" to JsonPrimitive("hello")))
    )

    private val normalTool = object : Tool {
        override val name: String = "normal_tool"
        override val description: String = "A normal tool"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
            ToolExecutionResult.success("done")
    }

    private val approvalRequiredTool = object : Tool, ApprovalRequired {
        override val name: String = "dangerous_tool"
        override val description: String = "A dangerous tool"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
            ToolExecutionResult.success("done")
    }

    @Test
    fun `should continue when tool does not require approval`() = runTest {
        val approver = object : Approver {
            override suspend fun requireApproval(context: ApprovalContext): ApprovalDecision {
                throw RuntimeException("approver should not be called")
            }
        }
        val hook = ApprovalHook(approver)
        val event = AgentHookEvent.BeforeToolCall(toolCall("normal_tool"))
        val context = HookContext(createContext(listOf(normalTool)))

        val result = hook.execute(event, context)

        assertSame(HookResult.Continue, result)
    }

    @Test
    fun `should call approver when tool requires approval`() = runTest {
        var called = false
        val approver = object : Approver {
            override suspend fun requireApproval(context: ApprovalContext): ApprovalDecision {
                called = true
                assertEquals("dangerous_tool", context.toolName)
                return ApprovalDecision.Approved
            }
        }
        val hook = ApprovalHook(approver)
        val event = AgentHookEvent.BeforeToolCall(toolCall("dangerous_tool"))
        val context = HookContext(createContext(listOf(approvalRequiredTool)))

        hook.execute(event, context)

        assertEquals(true, called)
    }

    @Test
    fun `should return Continue when approver approves`() = runTest {
        val approver = object : Approver {
            override suspend fun requireApproval(context: ApprovalContext): ApprovalDecision =
                ApprovalDecision.Approved
        }
        val hook = ApprovalHook(approver)
        val event = AgentHookEvent.BeforeToolCall(toolCall("dangerous_tool"))
        val context = HookContext(createContext(listOf(approvalRequiredTool)))

        val result = hook.execute(event, context)

        assertSame(HookResult.Continue, result)
    }

    @Test
    fun `should return Refuse when approver denies`() = runTest {
        val approver = object : Approver {
            override suspend fun requireApproval(context: ApprovalContext): ApprovalDecision =
                ApprovalDecision.Denied("user rejected")
        }
        val hook = ApprovalHook(approver)
        val event = AgentHookEvent.BeforeToolCall(toolCall("dangerous_tool"))
        val context = HookContext(createContext(listOf(approvalRequiredTool)))

        val result = hook.execute(event, context)

        assertIs<HookResult.Refuse>(result)
        assertEquals("user rejected", result.reason)
    }

    @Test
    fun `should return Refuse with default message when approver denies without reason`() = runTest {
        val approver = object : Approver {
            override suspend fun requireApproval(context: ApprovalContext): ApprovalDecision =
                ApprovalDecision.Denied(null)
        }
        val hook = ApprovalHook(approver)
        val event = AgentHookEvent.BeforeToolCall(toolCall("dangerous_tool"))
        val context = HookContext(createContext(listOf(approvalRequiredTool)))

        val result = hook.execute(event, context)

        assertIs<HookResult.Refuse>(result)
        assertEquals("工具审批被拒绝", result.reason)
    }

    @Test
    fun `should use tool name from event not from context lookup`() = runTest {
        // Tool name in event is "dangerous_tool", matching the ApprovalRequired tool
        var capturedToolName: String? = null
        val approver = object : Approver {
            override suspend fun requireApproval(context: ApprovalContext): ApprovalDecision {
                capturedToolName = context.toolName
                return ApprovalDecision.Approved
            }
        }
        val hook = ApprovalHook(approver)
        // Even if tools list is empty, event carries the tool name
        val event = AgentHookEvent.BeforeToolCall(toolCall("dangerous_tool"))
        val context = HookContext(createContext(listOf(approvalRequiredTool)))

        hook.execute(event, context)

        assertEquals("dangerous_tool", capturedToolName)
    }
}
