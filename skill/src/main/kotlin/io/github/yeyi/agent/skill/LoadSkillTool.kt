package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal class LoadSkillTool(
    private val registry: SkillRegistry
) : Tool {
    companion object {
        const val NAME: String = "load_skill"
    }

    override val name: String = NAME
    override val description: String = "加载技能详细指令"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(schema = """
        {
            "type": "object",
            "properties": {
                "skill_name": { "type": "string" }
            }
        }
    """.trimIndent())

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        val skillName = arguments.jsonObject["skill_name"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: return ToolExecutionResult(content = "Missing skill_name", isError = true)

        return registry.load(skillName)
            ?.let { ToolExecutionResult(content = it, isError = false) }
            ?: ToolExecutionResult(content = "Skill not found: $skillName", isError = true)
    }
}