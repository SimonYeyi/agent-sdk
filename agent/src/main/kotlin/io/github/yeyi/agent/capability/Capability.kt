package io.github.yeyi.agent.capability

import io.github.yeyi.agent.tool.ToolContext

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

public interface CapabilityContextFactory<Ctx : CapabilityContext> {

    public fun create(context: ToolContext): Ctx
}

/**
 * A named, routable unit of work that an LLM can invoke indirectly through
 * a Delegate Tool.
 *
 * Capabilities are registered into a [CapabilityRegistry] which acts as the
 * routing center: the registry knows the name → capability map, and
 * `execute` looks up the right one and delegates.
 *
 * `execute` returns [String] (the text the LLM will see) — errors propagate
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
public interface Capability<Ctx : CapabilityContext> {
    /** Routing key — also used by Delegate Tool to identify the capability. */
    public val name: String

    /** Human-readable description for the LLM to know when to delegate. */
    public val description: String

    /**
     * Execute this capability.
     *
     * @param context framework-built capability context
     * @return text the LLM will see
     * @throws Throwable any failure (including domain errors); the caller
     *   wraps non-CancellationException into `ToolExecutionResult(isError=true)`
     */
    public suspend fun activate(context: Ctx): String
}