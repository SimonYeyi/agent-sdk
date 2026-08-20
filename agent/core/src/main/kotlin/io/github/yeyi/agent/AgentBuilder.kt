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
    public var persona: Persona? = null
        private set
    private var maxIterations: Int = 20
    private var llmProvider: LlmProvider? = null
    private var memory: Memory = InMemoryMemory()
    private var maxRounds: Int = 20
    private var toolRegistry = ToolRegistry()

    private var hook: AgentHook = NoOpAgentHook
    private var modalityAdapter: ModalityAdapter? = null

    /** 设置最大迭代次数（LLM 调用次数），默认 20。 */
    public fun maxIterations(iterations: Int) {
        require(iterations > 0) { "maxIterations must be positive" }
        maxIterations = iterations
    }

    /** 设置 Agent 人设。 */
    public fun persona(persona: Persona) {
        this.persona = persona
    }

    /** 设置 LLM Provider。必须在 [build] 前调用。 */
    public fun llmProvider(provider: LlmProvider) {
        llmProvider = provider
    }

    /**
     * 设置 Memory 实现及历史轮次上限。
     *
     * @param memory 对话历史存储，默认 [InMemoryMemory]
     * @param maxRounds Memory 中保留的最大对话轮数（每轮含 user+assistant），超限后旧轮次会被摘要压缩
     */
    public fun memory(memory: Memory, maxRounds: Int = 20) {
        this.memory = memory
        this.maxRounds = maxRounds
    }

    /** 注册单个工具。 */
    public fun tool(tool: Tool) {
        toolRegistry.register(tool)
    }

    /** 注册多个工具。 */
    public fun tools(tools: Iterable<Tool>) {
        toolRegistry.register(tools)
    }

    /** 注册完整工具集。 */
    public fun tools(registry: ToolRegistry) {
        registry.register(toolRegistry.all())
        toolRegistry = registry
    }

    /**
     * @param hook 挂单个 hook，或挂一个已组合好的 hook 树。
     */
    public fun hook(hook: AgentHook) {
        this.hook = hook
    }

    /**
     * 设置多模态适配器。`ModalityAdapter` 在 LLM 请求边界完成"末条 User 的 Local
     * → Data resolve + 跨 round 占位 + 末条 ToolResult 拆 text"三件事。
     *
     * 未设置时 [build] 内默认 [DefaultModalityAdapter] (无构造参数)。
     */
    public fun modalityAdapter(adapter: ModalityAdapter) {
        this.modalityAdapter = adapter
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
        val adapter = modalityAdapter ?: DefaultModalityAdapter()

        return ReActAgent(
            persona = persona ?: Persona("You are a helpful assistant."),
            llmProvider = provider,
            toolRegistry = toolRegistry,
            memory = memory,
            modalityAdapter = adapter,
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
