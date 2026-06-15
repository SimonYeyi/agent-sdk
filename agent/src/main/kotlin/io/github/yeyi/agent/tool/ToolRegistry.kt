package io.github.yeyi.agent.tool

import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.ToolDefinition
import kotlinx.coroutines.CancellationException

/**
 * Centralized store for the tools an [io.github.yeyi.agent.Agent] can invoke.
 *
 * Owns the (name → [Tool]) map and the only allowed surface for callers to:
 * - **Register** tools during agent construction.
 * - **Execute** a [ToolCall] from the LLM, including the "not found" lookup and the
 *   exception-to-[ToolExecutionResult] translation.
 * - **Project** the registered tools to the [ToolDefinition] list sent to the LLM.
 *
 * Consumers outside this class MUST NOT hold a parallel list/map of tools: the registry
 * is the single source of truth for "which tools exist" and "what to do when the LLM
 * asks to call one". Keeping that contract lets the agent core stay a thin orchestrator
 * and lets extension modules (skill, mcp, ...) plug in by registering more tools.
 *
 * Insertion order is preserved (the underlying map is a [LinkedHashMap]) so the LLM
 * sees tools in the order the user declared them — useful for prompts that prime the
 * model toward earlier entries.
 */
public class ToolRegistry {
    private val byName: MutableMap<String, Tool> = LinkedHashMap()

    /**
     * Register a single [tool]. Throws [IllegalArgumentException] if a tool with the
     * same name is already registered, since a duplicate would make LLM-dispatched
     * tool calls ambiguous.
     */
    public fun register(tool: Tool) {
        require(tool.name !in byName) { "Duplicate tool name: ${tool.name}" }
        byName[tool.name] = tool
    }

    /** Register each tool in [tools] in iteration order. */
    public fun register(tools: Iterable<Tool>) {
        tools.forEach(::register)
    }

    /**
     * Resolve [call.name] to a registered tool and execute it.
     *
     * - Tool not found → returns [ToolExecutionResult] with `isError = true` and a
     *   message listing the registered names; the agent loop is NOT aborted.
     * - Tool throws a non-[CancellationException] → wrapped as
     *   [ToolExecutionResult] with `isError = true`.
     * - Tool throws a [CancellationException] → rethrown, never swallowed
     *   (structured concurrency contract).
     */
    internal suspend fun execute(call: ToolCall, context: ToolContext): ToolExecutionResult {
        val tool = byName[call.name]
            ?: return ToolExecutionResult(
                content = "Tool '${call.name}' not found. Available: ${byName.keys.joinToString()}",
                isError = true,
            )
        return try {
            tool.execute(call.arguments, context)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            ToolExecutionResult(content = "Tool error: ${t.message}", isError = true)
        }
    }

    /** Project registered tools to the LLM-facing [ToolDefinition] list, in declaration order. */
    internal fun definitions(): List<ToolDefinition> =
        byName.values.map { ToolDefinition(it.name, it.description, it.parametersSchema) }
}
