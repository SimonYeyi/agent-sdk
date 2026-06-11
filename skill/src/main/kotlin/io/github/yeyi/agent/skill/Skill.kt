package io.github.yeyi.agent.skill

/**
 * A named unit of [instructions] that the LLM can pull into context on demand.
 *
 * `Skill` is consumed by being adapted to a `Tool` (see [SkillTool]); the LLM invokes the
 * skill exactly like any other tool, and the tool's result is the skill's [instructions].
 * This means the body is loaded into the LLM context only when the model explicitly asks
 * for the skill — not on every turn.
 *
 * `Skill` carries no tools of its own. If the instructions mention needing certain tools
 * (e.g. "call `get_weather`"), the caller is expected to register those tools on the
 * `AgentBuilder` separately. This keeps `Skill` a pure data carrier and avoids coupling
 * a documentation unit to a specific toolset.
 */
public data class Skill(
    public val name: String,
    public val description: String,
    public val instructions: String,
)
