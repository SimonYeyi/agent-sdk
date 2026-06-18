package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal class LoadSkillTool(private val registry: SkillRegistry) : Tool {
    override val name: String = "load_skill"
    override val description: String by lazy { buildDescription() }
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        schema = """
        {
            "type": "object",
            "properties": {
                "skill_name": { "type": "string" }
            },
            "required": ["skill_name"]
        }
    """.trimIndent()
    )

    private fun buildDescription(): String = """
        |当需要加载以下技能时，调用本工具:
        |${registry.buildDescription()}
    """.trimMargin()

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val skillName = arguments.jsonObject["skill_name"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: return ToolExecutionResult(content = "Missing skill_name", isError = true)

        val skillContext = SkillContext(
            arguments = arguments,
            toolContext = context,
        )

        return registry.load(skillName, skillContext)
            ?.let { ToolExecutionResult(content = it) }
            ?: ToolExecutionResult(content = "Skill not found: $skillName", isError = true)
    }
}