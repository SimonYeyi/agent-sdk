package io.github.yeyi.agent

import io.github.yeyi.agent.internal.Logging
import io.github.yeyi.agent.llm.LlmClient
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.skill.Skill
import io.github.yeyi.agent.tool.Tool

/**
 * DSL builder for [Agent]. Obtain an instance via the top-level [agent] factory function.
 *
 * `build()` produces a [ReActAgent] configured from the current builder state.
 *
 * ## Tool merge order
 *
 * Tools are flattened into a single ordered list as: `tools + skills.flatMap { it.tools }`.
 * Concretely, all tools added via [tool] / [tools] appear first in declaration order, followed
 * by each skill's tools in skill declaration order. The resulting order is preserved into
 * [AgentConfig.tools] and forwarded to the LLM as the tool list, so callers that care about
 * the order in which the model sees tools can rely on this contract.
 *
 * If any two tools (across both direct calls and skill contributions) share the same [Tool.name],
 * `build()` throws [IllegalArgumentException] — duplicate tool names would otherwise be
 * ambiguous when the LLM dispatches a tool call.
 *
 * ## systemPrompt concatenation
 *
 * The final [AgentConfig.systemPrompt] is the concatenation of the user-supplied [systemPrompt]
 * followed by every non-blank `systemPromptFragment` from each declared [Skill], with each
 * non-blank fragment preceded by a `"\n\n"` separator. Blank fragments (empty string or
 * whitespace-only) are skipped entirely, so they neither contribute a separator nor a payload.
 *
 * ## Validation
 *
 * - [llmClient] must be set before calling [build]; otherwise [IllegalArgumentException] is thrown.
 * - A warning is logged when the agent is built with an empty [systemPrompt], no tools, and no
 *   skills — such an agent can only do pure chat and is usually a misconfiguration.
 */
public class AgentBuilder {
    public var systemPrompt: String = ""
    public var llmClient: LlmClient? = null
    public var maxIterations: Int = 10

    private val tools: MutableList<Tool> = mutableListOf()
    private val skills: MutableList<Skill> = mutableListOf()
    private var memory: Memory = InMemoryMemory()
    private val hooks: MutableList<AgentHook> = mutableListOf()

    public fun tool(t: Tool) {
        tools += t
    }

    public fun tools(ts: Iterable<Tool>) {
        tools += ts
    }

    public fun skill(s: Skill) {
        skills += s
    }

    public fun skills(ss: Iterable<Skill>) {
        skills += ss
    }

    public fun memory(m: Memory) {
        memory = m
    }

    public fun hook(h: AgentHook) {
        hooks += h
    }

    /**
     * Terminal operation: snapshots the current builder state into an [AgentConfig] and returns
     * a fresh [ReActAgent] bound to that config.
     *
     * Re-calling `build()` on the same builder produces two independent agents (the captured
     * config is copied, not shared), so the builder is safe to call `build()` on multiple times.
     *
     * @throws IllegalArgumentException if [llmClient] has not been set, or if duplicate tool
     *   names are detected after flattening direct tools with skill tools.
     */
    public fun build(): Agent {
        val client = requireNotNull(llmClient) { "llmClient must be set" }

        if (systemPrompt.isBlank() && tools.isEmpty() && skills.isEmpty()) {
            Logging.warn(
                "AgentBuilder",
                "Agent has no system prompt, no tools, and no skills; useful only for pure chat."
            )
        }

        // Skill 展开为 systemPrompt + tools
        val combinedPrompt = buildString {
            append(systemPrompt)
            for (s in skills) {
                if (s.systemPromptFragment.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(s.systemPromptFragment)
                }
            }
        }
        val allTools: List<Tool> = tools + skills.flatMap { it.tools }
        val byName = allTools.groupBy { it.name }
        require(byName.all { it.value.size == 1 }) {
            "Duplicate tool names after flattening: ${byName.filter { it.value.size > 1 }.keys}"
        }

        val config = AgentConfig(
            systemPrompt = combinedPrompt,
            llmClient = client,
            tools = allTools,
            memory = memory,
            maxIterations = maxIterations,
            hooks = hooks.toList()
        )
        return ReActAgent(config)
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
 *     skill(weatherSkill)
 * }
 * ```
 *
 * @param block configuration block executed against a fresh [AgentBuilder].
 * @return a new [Agent] (specifically a [ReActAgent]) ready to run.
 * @throws IllegalArgumentException if [AgentBuilder.llmClient] is not set inside [block], or if
 *   duplicate tool names are detected after merging direct tools with skill-provided tools.
 */
public fun agent(block: AgentBuilder.() -> Unit): Agent =
    AgentBuilder().apply(block).build()
