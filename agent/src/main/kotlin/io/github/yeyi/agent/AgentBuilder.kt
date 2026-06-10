package io.github.yeyi.agent

import io.github.yeyi.agent.internal.Logging
import io.github.yeyi.agent.llm.LlmClient
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry

/**
 * DSL builder for [Agent]. Obtain an instance via the top-level [agent] factory function.
 *
 * `build()` produces a [ReActAgent] configured from the current builder state.
 *
 * ## Validation
 *
 * - [llmClient] must be set before calling [build]; otherwise [IllegalArgumentException] is thrown.
 * - Registering two tools with the same name throws [IllegalArgumentException] at registration
 *   time (the [ToolRegistry] rejects duplicates eagerly so an ambiguous name can never reach
 *   the LLM).
 * - A warning is logged when the agent is built with an empty [systemPrompt] and no tools —
 *   such an agent can only do pure chat and is usually a misconfiguration.
 *
 * Skills are NOT built in here: that concept is a higher-level composition (see the `skill`
 * module's `AgentBuilder.skill(s)` extension). The core builder only deals with raw tools,
 * memory, hooks, and the LLM client.
 */
public class AgentBuilder {
    public var systemPrompt: String = ""
    public var llmClient: LlmClient? = null
    public var maxIterations: Int = 10

    private val tools = ToolRegistry()
    private var memory: Memory = InMemoryMemory()
    private val hooks: MutableList<AgentHook> = mutableListOf()

    public fun tool(t: Tool) {
        tools.register(t)
    }

    public fun tools(ts: Iterable<Tool>) {
        tools.registerAll(ts)
    }

    public fun memory(m: Memory) {
        memory = m
    }

    public fun hook(h: AgentHook) {
        hooks += h
    }

    /**
     * Terminal operation: snapshots the current builder state and returns a fresh [ReActAgent].
     *
     * Re-calling `build()` on the same builder produces two independent agents (the captured
     * tool registry, memory, and hook list are passed through by reference, so reusing
     * the builder after build will not affect previously built agents via this code path).
     *
     * @throws IllegalArgumentException if [llmClient] has not been set.
     */
    public fun build(): Agent {
        val client = requireNotNull(llmClient) { "llmClient must be set" }

        if (systemPrompt.isBlank() && tools.names().isEmpty()) {
            Logging.warn(
                "AgentBuilder",
                "Agent has no system prompt and no tools; useful only for pure chat."
            )
        }

        return ReActAgent(
            systemPrompt = systemPrompt,
            llmClient = client,
            tools = tools,
            memory = memory,
            maxIterations = maxIterations,
            hooks = hooks.toList(),
        )
    }
}

/**
 * Top-level DSL factory: builds and returns an [Agent] by applying [block] to a fresh
 * [AgentBuilder] and immediately calling `build()`.
 *
 * Usage:
 * ```kotlin
 * val a = agent {
 *     systemPrompt = "You are a helpful assistant."
 *     llmClient = openAIClient
 *     tool(WeatherTool())
 * }
 * ```
 *
 * @param block configuration block executed against a fresh [AgentBuilder].
 * @return a new [Agent] (specifically a [ReActAgent]) ready to run.
 * @throws IllegalArgumentException if [AgentBuilder.llmClient] is not set inside [block].
 */
public fun agent(block: AgentBuilder.() -> Unit): Agent =
    AgentBuilder().apply(block).build()
