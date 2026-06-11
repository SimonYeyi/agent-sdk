package io.github.yeyi.agent.skill

/**
 * A named, lazily-loaded documentation unit that the LLM can pull into context on demand.
 *
 * `Skill` is consumed by being adapted to a `Tool` (see [SkillTool]); the LLM invokes the
 * skill exactly like any other tool, and the tool's result is whatever [load] returns. The
 * body is loaded into the LLM context only when the model explicitly asks for the skill —
 * not on every turn.
 *
 * `load` is an abstract method rather than a string field so that different
 * implementations can choose where the content lives and when to materialize it:
 * - read it from a file (e.g. a markdown skill with frontmatter)
 * - fetch it from a network endpoint
 * - compute it from application state
 * - hard-code it for trivial cases
 *
 * Implementations should treat [load] as idempotent and side-effect free: the same
 * `Skill` may be invoked many times in one agent run, and the [SkillTool] executor will
 * call [load] each time.
 *
 * ### Example
 * ```kotlin
 * class WeatherSkill : Skill {
 *     override val name = "weather"
 *     override val description = "天气查询助手"
 *     override fun load() = "你是天气助手,使用 get_weather / get_forecast 工具回答问题。"
 * }
 * ```
 *
 * Note: the SDK intentionally does not ship a `StringSkill` convenience class — every
 * skill is expected to declare its own concrete `Skill` class so that the load strategy
 * is explicit and type-checked.
 */
public interface Skill {
    public val name: String
    public val description: String
    public fun load(): String
}
