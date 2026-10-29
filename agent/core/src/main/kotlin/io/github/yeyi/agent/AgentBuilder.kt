package io.github.yeyi.agent

import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.MediaArchivable
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.modality.DefaultModalityAdapter
import io.github.yeyi.agent.modality.ModalityAdapter
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
 * Plugins are NOT built in here: that concept is a higher-level composition (see
 * [plugin] for the plugin extension point). The core builder only deals with persona, tools, memory, hook, and the LLM provider.
 */
public class AgentBuilder {
    private var persona: Persona = Persona("You are a helpful assistant.")
    private var llmProvider: LlmProvider? = null
    private var memory: Memory = InMemoryMemory()
    private var maxRounds: Int = 20
    private var maxIterations: Int = 20
    private var modalityAdapter: ModalityAdapter? = null
    private var hook: AgentHook = NoOpAgentHook

    private var toolRegistry = ToolRegistry()
    private val plugins = mutableMapOf<String, AgentPlugin<*>>()

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

    /** 设置最大迭代次数（LLM 调用次数），默认 20。 */
    public fun maxIterations(iterations: Int) {
        require(iterations > 0) { "maxIterations must be positive" }
        maxIterations = iterations
    }

    /** 设置多模态适配器。用于自定义模型对于历史消息中 media 的可见性 */
    public fun modalityAdapter(adapter: ModalityAdapter) {
        this.modalityAdapter = adapter
    }

    /**
     * @param hook 挂单个 hook，或挂一个已组合好的 hook 树。
     */
    public fun hook(hook: AgentHook) {
        this.hook = hook
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
     * 安装插件。
     *
     * 用法1 — 插件自带的 config + block 配置：
     * ```kotlin
     * plugin(MyPlugin()) {
     *     configItem(...)
     * }
     * ```
     *
     * 用法2 — 外部已有 config：
     * ```kotlin
     * val config = MyConfig().apply { configItem(...) }
     * plugin(MyPlugin(config))
     * ```
     *
     * configure 在 `plugin()` 注册时立即执行；install 统一在 [build] 时执行。
     *
     * @throws AgentPlugin.InstallException if a plugin with the same id is already installed.
     */
    public fun <C : Any, P : AgentPlugin<C>> plugin(plugin: P, configure: C.() -> Unit = {}) {
        if (plugin.id in plugins) {
            throw AgentPlugin.InstallException(
                "Plugin with id '${plugin.id}' is already installed. Only one plugin of each type is allowed.",
            )
        }
        plugin.configure(configure)
        plugins[plugin.id] = plugin
    }

    /** 按 id 反查已注册插件（泛型能力，不感知具体插件）。 */
    public fun findPlugin(id: String): AgentPlugin<*>? = plugins[id]

    private fun installPlugins() {
        val pluginContext = object : AgentPluginContext {
            override fun registerTool(tool: Tool) = toolRegistry.register(tool)
            override fun appendPersona(label: String, content: String) {
                persona.extra(content, label)
            }
        }
        plugins.values.forEach { p ->
            try {
                p.install(pluginContext)
            } catch (e: Throwable) {
                throw AgentPlugin.InstallException("Plugin '${p.id}' installation failed", e)
            }
        }
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
        val modalityAdapter = modalityAdapter
            ?: DefaultModalityAdapter((memory as? MediaArchivable)?.mediaArchive)

        installPlugins()

        return ReActAgent(
            persona = persona,
            llmProvider = provider,
            memory = memory,
            maxRounds = maxRounds,
            maxIterations = maxIterations,
            modalityAdapter = modalityAdapter,
            hook = hook,
            toolRegistry = toolRegistry,
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
