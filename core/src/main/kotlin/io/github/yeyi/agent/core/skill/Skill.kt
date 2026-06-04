package io.github.yeyi.agent.core.skill

import io.github.yeyi.agent.core.tool.Tool

/**
 * 可复用的能力包:将一段 system prompt 片段和一组 tool 绑定在一个有名字的 bundle 里。
 *
 * Agent 可通过 `agent { skill(...) }` 引入 Skill;SDK 会把 `systemPromptFragment` 拼接到
 * Agent 的 system prompt 后面,并把 `tools` 合并到 Agent 的 tool 列表。
 *
 * `description` 用于向调用方描述该 Skill 的用途(日志、路由、UI 展示等),不影响行为。
 */
public data class Skill(
    public val name: String,
    public val description: String,
    public val systemPromptFragment: String = "",
    public val tools: List<Tool> = emptyList()
)
