package io.github.yeyi.agent.capability

import io.github.yeyi.agent.tool.ToolContext
import kotlinx.serialization.KSerializer

/**
 * Marker interface for the context a [Capability] needs at execution time.
 *
 * Concrete implementations (e.g. `SubagentCapabilityContext`,
 * `SkillCapabilityContext`) live in their respective modules —
 * `agent` only knows about the marker. The factory pattern in
 * [CapabilityContextFactory] is the only bridge between agent-internal
 * `ToolContext` and capability-side contexts.
 */
public interface CapabilityContext

/**
 * Factory that bridges agent-side [ToolContext] to capability-specific [Ctx].
 *
 * @param Ctx the capability-specific context type
 */
public interface CapabilityContextFactory<Ctx : CapabilityContext> {
    /**
     * Create a capability context from the agent's runtime tool context.
     *
     * @param context the agent's runtime tool context
     */
    public fun create(context: ToolContext): Ctx
}

/**
 * A named, routable unit of work that an LLM can invoke indirectly through
 * a Delegate Tool.
 *
 * A Capability is one member of a routable *category* (e.g. several
 * subagents). The category's call shape — what `arguments` look like and how
 * they're declared to the LLM — is owned by the [CapabilityAdapter] that
 * wires the registry into the agent, NOT by each individual capability.
 * Individual capabilities are responsible for parsing the `arguments` they
 * receive.
 *
 * `activate` returns [String] (the text the LLM will see) — errors propagate
 * as exceptions and are caught by the calling layer (ToolRegistry already
 * does this for `Tool.execute`).
 *
 * The type parameter [Ctx] lets each capability family bring its own
 * context shape: a Subagent needs `LlmProvider`, a Skill may need only raw
 * arguments, etc. The factory pattern in [CapabilityContextFactory] is
 * what wires [Ctx] from a runtime `ToolContext`.
 *
 * @param Ctx the capability-specific context shape
 */
public interface Capability<T : Any, Ctx : CapabilityContext> {
    /** Routing key — also used by Delegate Tool to identify the capability. */
    public val name: String

    /** Human-readable description for the LLM to know when to delegate. */
    public val description: String

    /**
     * Execute this capability.
     *
     * @param arguments JSON object forwarded by the calling tool (LLM-provided
     *   call payload; shape is defined by the Adapter's `argumentsSchema`)
     * @param context framework-built capability context
     * @return text the LLM will see
     * @throws Throwable any failure (including domain errors); the caller
     *   wraps non-CancellationException into `ToolExecutionResult(isError=true)`
     */
    public suspend fun activate(arguments: T?, context: Ctx): String
}

/**
 * Capability 参数 schema 描述。
 *
 * @param T arguments 的类型
 */
public interface CapabilityArguments<T> {
    /** JSON Schema 字符串，用于告诉 LLM arguments 的结构。 */
    public val schema: String

    /** Kotlinx Serialization serializer，用于解析 LLM 传来的 JSON。 */
    public val serializer: KSerializer<T>
}
