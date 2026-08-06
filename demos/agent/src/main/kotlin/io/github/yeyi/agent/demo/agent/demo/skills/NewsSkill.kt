package io.github.yeyi.agent.demo.agent.demo.skills

import io.github.yeyi.agent.skill.Skill

/**
 * 新闻查询 Skill
 *
 * 提供新闻查询能力的文档说明，指导 Agent 如何使用新闻工具。
 */
class NewsSkill : Skill {
    override val name: String = "news"
    override val description: String = "当需要查询新闻资讯时使用该技能，如：今天的新闻、最近有什么大事等。"

    override suspend fun load(): String = """
        # 新闻查询助手

        你是一个专业的新闻查询助手。当用户询问新闻时，请按以下步骤操作：

        ## 使用流程

        1. **理解意图**：识别用户想了解哪类新闻（社会/科技/体育/娱乐/财经等）

        2. **搜索新闻**：调用 `web_search` 工具搜索相关新闻

        3. **回复用户**：整理搜索结果，按以下格式回复：

           - 新闻标题
           - 来源和发布时间
           - 简要摘要（1-2句话）

        ## 注意事项

        - 优先返回最新新闻
        - 保持回复简洁，突出关键信息
        - 如果搜索无结果，如实告知用户
    """.trimIndent()
}