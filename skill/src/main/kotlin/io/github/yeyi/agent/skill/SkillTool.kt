package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement

/**
 * Adapter that exposes a [Skill] as a [Tool] for the LLM.
 *
 * The tool's [execute] calls [Skill.load] each invocation and returns the result. The
 * tool's [name] is `skill_<skill.name>` so the LLM sees a clear, namespaced handle and
 * the body is loaded only when the model explicitly invokes the skill.
 */
public class SkillTool internal constructor(
    private val skill: Skill,
) : Tool {
    override val name: String = "skill_${skill.name}"
    override val description: String = skill.description
    override val parametersSchema: ToolParameters = ToolParameters.Empty

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
        ToolExecutionResult(content = skill.load(), isError = false)
}
