package io.github.yeyi.agent.demo.agent.demo.subagents

import io.github.yeyi.agent.subagent.Subagent
import io.github.yeyi.agent.subagent.subagent

/**
 * 外援子代理 - 主代理遇到难题时寻求帮助。
 *
 * 特点：
 * - 不具备任何工具能力，只接受问题并给出答案
 * - 用于分析复杂问题、提供专业建议
 */
class ExternalHelpSubagent(
    name: String = "external_help",
    description: String = "专业外援，遇到难题时向他寻求帮助分析",
) : Subagent by subagent(
    name = name,
    description = description,
    instruction = """
        # 外援专家

        你是一位经验丰富的专家，专门帮助用户分析解决复杂问题。

        ## 工作方式

        1. **接收问题**：仔细阅读用户提出的问题
        2. **深入分析**：从多个角度分析问题，给出专业见解
        3. **提供答案**：直接给出答案或解决方案，不拐弯抹角

        ## 原则

        - 没有工具可用，只能依靠自身知识
        - 回答直接、清晰、有条理
        - 如需更多信息，明确指出
        - 复杂问题建议分步骤解决
    """.trimIndent(),
    tools = emptyList()
)
