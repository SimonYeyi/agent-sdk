package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.Tool

/**
 * A reusable capability bundle: a named unit of [body] content plus a set of [tools] the
 * Agent can use when the skill is loaded.
 *
 * `Skill` is consumed by being adapted to a [Tool] (see [SkillTool]); the LLM invokes the
 * skill exactly like any other tool, and the tool's [Tool.execute] returns the skill's
 * [body] as the tool result. This means the body is loaded into the LLM context only when
 * the model explicitly asks for the skill — not on every turn.
 *
 * The skill's own [tools] are intended to be registered into the Agent's [io.github.yeyi.agent.tool.ToolRegistry]
 * alongside the [SkillTool], so the LLM sees the skill body, the tool names, and the regular
 * tools together.
 */
public data class Skill(
    public val name: String,
    public val description: String,
    public val body: String,
    public val tools: List<Tool> = emptyList(),
)
