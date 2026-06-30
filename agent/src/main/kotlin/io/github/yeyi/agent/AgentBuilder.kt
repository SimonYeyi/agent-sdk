package io.github.yeyi.agent

import io.github.yeyi.agent.llm.LlmProvider
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
 * - [llmProvider] must be set before calling [build]; otherwise [IllegalArgumentException] is thrown.
 * - Registering two tools with the same name throws [IllegalArgumentException] at registration
 *   time (the [ToolRegistry] rejects duplicates eagerly so an ambiguous name can never reach
 *   the LLM).
 *   such an agent can only do pure chat and is usually a misconfiguration.
 *
 * Skills are NOT built in here: that concept is a higher-level composition (see the `skill`
 * module's `AgentBuilder.skill(s)` extension). The core builder only deals with raw tools,
 * memory, hooks, and the LLM provider.
 */
public class AgentBuilder {
    private var maxIterations: Int = 20
    public var persona: Persona? = null
        private set
    private var llmProvider: LlmProvider? = null
    private var memory: Memory = InMemoryMemory()
    private var maxRounds: Int = 20
    private val toolRegistry = ToolRegistry()

    private var hook: AgentHook = NoOpAgentHook

    public fun maxIterations(iterations: Int) {
        require(iterations > 0) { "maxIterations must be positive" }
        maxIterations = iterations
    }

    public fun persona(persona: Persona) {
        this.persona = persona
    }

    public fun llmProvider(provider: LlmProvider) {
        llmProvider = provider
    }

    public fun memory(memory: Memory, maxRounds: Int = 20) {
        this.memory = memory
        this.maxRounds = maxRounds
    }

    public fun tool(tool: Tool) {
        toolRegistry.register(tool)
    }

    public fun tools(tools: Iterable<Tool>) {
        toolRegistry.register(tools)
    }

    /**
     * @param hook 挂单个 hook，或挂一个已组合好的 hook 树。
     */
    public fun hook(hook: AgentHook) {
        this.hook = hook
    }

    /**
     * Terminal operation: snapshots the current builder state and returns a fresh [ReActAgent].
     *
     * Re-calling `build()` on the same builder produces two independent agents (the captured
     * tool registry, memory, and hook are passed through by reference, so reusing
     * the builder after build will not affect previously built agents via this code path).
     *
     * @throws IllegalArgumentException if [llmProvider] has not been set.
     */
    public fun build(): Agent {
        val provider = requireNotNull(llmProvider) { "llmProvider must be set" }

        return ReActAgent(
            persona = persona ?: Persona("You are a helpful assistant."),
            llmProvider = provider,
            toolRegistry = toolRegistry,
            memory = memory,
            maxRounds = maxRounds,
            maxIterations = maxIterations,
            hook = hook,
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
 *     persona(Persona(role = "You are a helpful assistant."))
 *     llmProvider(openAiProvider)
 *     tool(WeatherTool())
 * }
 * ```
 *
 * @param block configuration block executed against a fresh [AgentBuilder].
 * @return a new [Agent] (specifically a [ReActAgent]) ready to run.
 * @throws IllegalArgumentException if [AgentBuilder.llmProvider] is not set inside [block].
 */
public fun agent(block: AgentBuilder.() -> Unit): Agent =
    AgentBuilder().apply(block).build()
