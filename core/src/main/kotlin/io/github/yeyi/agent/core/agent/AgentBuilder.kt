package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.internal.Logging
import io.github.yeyi.agent.core.llm.LlmClient
import io.github.yeyi.agent.core.memory.InMemoryMemory
import io.github.yeyi.agent.core.memory.Memory
import io.github.yeyi.agent.core.skill.Skill
import io.github.yeyi.agent.core.tool.Tool

public class AgentBuilder {
    public var systemPrompt: String = ""
    public var llmClient: LlmClient? = null
    public var maxIterations: Int = 10

    private val tools: MutableList<Tool> = mutableListOf()
    private val skills: MutableList<Skill> = mutableListOf()
    private var memoryFactory: () -> Memory = { InMemoryMemory() }
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

    public fun memory(f: () -> Memory) {
        memoryFactory = f
    }

    public fun hook(h: AgentHook) {
        hooks += h
    }

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
            memoryFactory = memoryFactory,
            maxIterations = maxIterations,
            hooks = hooks.toList()
        )
        return ReActAgent(config)
    }
}

public fun agent(block: AgentBuilder.() -> Unit): Agent =
    AgentBuilder().apply(block).build()
