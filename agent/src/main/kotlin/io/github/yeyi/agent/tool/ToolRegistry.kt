package io.github.yeyi.agent.tool

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.ToolDefinition
import io.github.yeyi.agent.log.Logging
import io.github.yeyi.agent.log.agent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

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
public class ToolRegistry : ToolDispatcher {
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

    public fun all(): List<Tool> = byName.values.toList()

    override suspend fun dispatch(
        name: String,
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val tool = byName[name]
            ?: return ToolExecutionResult.error(
                AgentException.ToolNotFound(
                    name,
                    byName.keys
                ).message
            )
        return try {
            tool.execute(arguments, context)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            val message = "Tool execute error: name=${name}, arguments=$arguments"
            Logging.agent().warn(message, t)
            ToolExecutionResult.error("$message ${t.message}")
        }
    }

    /** Project registered tools to the LLM-facing [ToolDefinition] list, in declaration order. */
    internal fun definitions(): List<ToolDefinition> =
        byName.values.map { ToolDefinition(it.name, it.description, it.parametersSchema) }
}
