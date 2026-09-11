package io.github.yeyi.agent.approval

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.hook.AgentHookEvent
import io.github.yeyi.agent.hook.HookContext
import io.github.yeyi.agent.hook.HookResult
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.DelegateTarget
import io.github.yeyi.agent.tool.DelegatingTool
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("done")
    }

    private val approvalRequiredTool = object : Tool, Approvable {
        override val name: String = "dangerous_tool"
        override val description: String = "A dangerous tool"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("done")
    }

    @Test
    fun `should continue when tool does not require approval`() = runTest {
        val approver = object : Approver {
            override suspend fun approval(context: ApprovalContext): ApprovalDecision {
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
            override suspend fun approval(context: ApprovalContext): ApprovalDecision {
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
            override suspend fun approval(context: ApprovalContext): ApprovalDecision =
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
            override suspend fun approval(context: ApprovalContext): ApprovalDecision =
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
            override suspend fun approval(context: ApprovalContext): ApprovalDecision =
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
        // Tool name in event is "dangerous_tool", matching the Approvable tool
        var capturedToolName: String? = null
        val approver = object : Approver {
            override suspend fun approval(context: ApprovalContext): ApprovalDecision {
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

    @Test
    fun `should skip approver when requiresApproval returns false based on arguments`() = runTest {
        // 工具根据参数决定是否需要审批：cmd 以 "rm" 开头才需要
        val conditionalTool = object : Tool, Approvable {
            override val name: String = "bash"
            override val description: String = "Run a shell command"
            override val parametersSchema: ToolParameters = ToolParameters.Empty
            override fun requiresApproval(arguments: JsonElement): Boolean {
                val cmd = (arguments as? JsonObject)?.get("msg")?.let { (it as? JsonPrimitive)?.content }
                return cmd?.startsWith("rm") == true
            }
            override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                ToolExecutionResult.success("done")
        }

        val approver = object : Approver {
            override suspend fun approval(context: ApprovalContext): ApprovalDecision {
                throw RuntimeException("approver should not be called for safe arguments")
            }
        }
        val hook = ApprovalHook(approver)
        // arguments.msg = "hello"（test 工具调用 helper 固定值），requiresApproval 返回 false
        val event = AgentHookEvent.BeforeToolCall(toolCall("bash"))
        val context = HookContext(createContext(listOf(conditionalTool)))

        val result = hook.execute(event, context)

        assertSame(HookResult.Continue, result)
    }

    @Test
    fun `should call approver when requiresApproval returns true based on arguments`() = runTest {
        val conditionalTool = object : Tool, Approvable {
            override val name: String = "bash"
            override val description: String = "Run a shell command"
            override val parametersSchema: ToolParameters = ToolParameters.Empty
            override fun requiresApproval(arguments: JsonElement): Boolean {
                val cmd = (arguments as? JsonObject)?.get("msg")?.let { (it as? JsonPrimitive)?.content }
                return cmd?.startsWith("rm") == true
            }
            override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                ToolExecutionResult.success("done")
        }

        var called = false
        val approver = object : Approver {
            override suspend fun approval(context: ApprovalContext): ApprovalDecision {
                called = true
                return ApprovalDecision.Approved
            }
        }
        val hook = ApprovalHook(approver)
        // arguments.msg = "hello" 不以 rm 开头；但此处手工构造一个以 rm 开头的
        val rmCall = ToolCall(
            id = "call-2",
            name = "bash",
            arguments = JsonObject(mapOf("msg" to JsonPrimitive("rm -rf /")))
        )
        val event = AgentHookEvent.BeforeToolCall(rmCall)
        val context = HookContext(createContext(listOf(conditionalTool)))

        hook.execute(event, context)

        assertEquals(true, called)
    }

    // ---- 委托工具穿透 ----

    /**
     * 委托工具：解析目标 + 剥离路由字段，返回底层 Tool 及其参数。
     * 参数结构 {tool_name, arguments}，arguments 即底层参数。解析失败抛异常。
     */
    private class FakeDelegateTool(
        override val name: String,
        private val target: Tool,
    ) : Tool, DelegatingTool {
        override val description: String = "delegate"
        override val parametersSchema: ToolParameters = ToolParameters.Empty

        override fun resolveTarget(arguments: JsonElement): DelegateTarget {
            val obj = arguments as? JsonObject
                ?: throw IllegalArgumentException("arguments must be a JsonObject")
            val innerArgs = obj["arguments"]
                ?: throw IllegalArgumentException("Missing 'arguments'")
            return DelegateTarget(target, innerArgs)
        }

        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
            val resolved = resolveTarget(arguments)
            return resolved.tool.execute(resolved.arguments, context)
        }
    }

    private val innerApprovableTool = object : Tool, Approvable {
        override val name: String = "dangerous_inner"
        override val description: String = "inner dangerous tool"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("ok")
    }

    private val innerNormalTool = object : Tool {
        override val name: String = "safe_inner"
        override val description: String = "inner safe tool"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("ok")
    }

    @Test
    fun `should trigger approval via delegate when target tool is Approvable`() = runTest {
        val delegate = FakeDelegateTool("delegate", innerApprovableTool)
        var captured: ApprovalContext? = null
        val approver = object : Approver {
            override suspend fun approval(context: ApprovalContext): ApprovalDecision {
                captured = context
                return ApprovalDecision.Approved
            }
        }
        val hook = ApprovalHook(approver)
        // 外层委托参数 {tool_name, arguments}；arguments 里有 msg=hello
        val call = ToolCall(
            id = "c-d",
            name = "delegate",
            arguments = JsonObject(mapOf("tool_name" to JsonPrimitive("dangerous_inner"), "arguments" to JsonObject(mapOf("msg" to JsonPrimitive("hello")))))
        )
        val event = AgentHookEvent.BeforeToolCall(call)
        val context = HookContext(createContext(listOf(delegate)))

        val result = hook.execute(event, context)

        assertSame(HookResult.Continue, result)
        // 应以底层工具名 + 底层参数触发审批
        assertEquals("dangerous_inner", captured?.toolName)
        assertEquals(JsonObject(mapOf("msg" to JsonPrimitive("hello"))), captured?.toolArguments)
    }

    @Test
    fun `should pass through delegate when target tool is not Approvable`() = runTest {
        val delegate = FakeDelegateTool("delegate", innerNormalTool)
        val approver = object : Approver {
            override suspend fun approval(context: ApprovalContext): ApprovalDecision {
                throw RuntimeException("approver should not be called for non-Approvable target")
            }
        }
        val hook = ApprovalHook(approver)
        val call = ToolCall(
            id = "c-d2",
            name = "delegate",
            arguments = JsonObject(mapOf("tool_name" to JsonPrimitive("safe_inner"), "arguments" to JsonObject(mapOf("msg" to JsonPrimitive("hello")))))
        )
        val event = AgentHookEvent.BeforeToolCall(call)
        val context = HookContext(createContext(listOf(delegate)))

        val result = hook.execute(event, context)

        assertSame(HookResult.Continue, result)
    }

    @Test
    fun `should refuse via delegate when approver denies target tool`() = runTest {
        val delegate = FakeDelegateTool("delegate", innerApprovableTool)
        val approver = object : Approver {
            override suspend fun approval(context: ApprovalContext): ApprovalDecision =
                ApprovalDecision.Denied("denied inner")
        }
        val hook = ApprovalHook(approver)
        val call = ToolCall(
            id = "c-d3",
            name = "delegate",
            arguments = JsonObject(mapOf("tool_name" to JsonPrimitive("dangerous_inner"), "arguments" to JsonObject(mapOf("msg" to JsonPrimitive("hello")))))
        )
        val event = AgentHookEvent.BeforeToolCall(call)
        val context = HookContext(createContext(listOf(delegate)))

        val result = hook.execute(event, context)

        assertIs<HookResult.Refuse>(result)
        assertEquals("denied inner", result.reason)
    }

    @Test
    fun `should throw when delegate target cannot be resolved`() = runTest {
        // 无法解析（缺 arguments 字段）→ 抛异常，由上层全局捕获，不触发 approver
        val delegate = FakeDelegateTool("delegate", innerApprovableTool)
        val approver = object : Approver {
            override suspend fun approval(context: ApprovalContext): ApprovalDecision {
                throw RuntimeException("approver should not be called when delegate unresolvable")
            }
        }
        val hook = ApprovalHook(approver)
        val call = ToolCall(
            id = "c-d4",
            name = "delegate",
            arguments = JsonObject(mapOf("tool_name" to JsonPrimitive("dangerous_inner"))) // 缺 arguments
        )
        val event = AgentHookEvent.BeforeToolCall(call)
        val context = HookContext(createContext(listOf(delegate)))

        assertFailsWith<IllegalArgumentException> {
            hook.execute(event, context)
        }
    }
}
