package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.Tool

/**
 * `skill { }` DSL 的状态容器。由 `skill(name, block)` 工厂函数创建并填充。
 *
 * 典型用法:
 * ```
 * val weatherSkill = skill("weather") {
 *     description = "天气助手"
 *     systemPromptFragment = "你负责查询天气"
 *     tool(GetWeatherTool())
 * }
 * ```
 */
public class SkillBuilder {
    public var description: String = ""
    public var systemPromptFragment: String = ""
    private val tools: MutableList<Tool> = mutableListOf()

    /** 注册一个 tool 到该 Skill。多次调用会按调用顺序累积。 */
    public fun tool(t: Tool) {
        tools += t
    }

    /**
     * 构造不可变的 [Skill]。`name` 由 `skill(name) { }` 工厂传入——这里不放在 builder
     * 上是因为 name 在 DSL 块外部已经确定,放在参数里更明确。
     */
    public fun build(name: String): Skill = Skill(
        name = name,
        description = description,
        systemPromptFragment = systemPromptFragment,
        tools = tools.toList()
    )
}

/**
 * 顶层 DSL 工厂,创建一个 [Skill]。
 *
 * @param name Skill 名称(在 agent 内唯一)
 * @param block 配置 block,设置 description / systemPromptFragment / 注册 tool
 */
public fun skill(name: String, block: SkillBuilder.() -> Unit): Skill =
    SkillBuilder().apply(block).build(name)
