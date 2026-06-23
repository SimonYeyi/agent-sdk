package io.github.yeyi.agent.capability

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal class LoadCapabilityTool<Ctx : CapabilityContext, C : Capability<Ctx>>(
    private val registry: CapabilityRegistry<Ctx, C>,
    private val capabilityContextFactory: CapabilityContextFactory<Ctx>
) : Tool {
    private val capabilityName = registry.capabilityName
    private val propertyKey = "${capabilityName}_name"
    override val name: String = "load_$capabilityName"

    override val description: String by lazy {
        """
        |当需要调用以下 $capabilityName 时，调用本工具:
        |${registry.all().joinToString("\n") { "- ${it.name}: ${it.description}" }}
    """.trimMargin()
    }

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        schema = """
        {
            "type": "object",
            "properties": {
                "$propertyKey": { "type": "string" }
            },
            "required": ["$propertyKey"]
        }
    """.trimIndent()
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val capabilityName = arguments.jsonObject[propertyKey]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: return ToolExecutionResult(
                content = "Missing $propertyKey",
                isError = true
            )

        val capabilityContext = capabilityContextFactory.create(context)

        return registry.all().find { it.name == capabilityName }
            ?.let { ToolExecutionResult(it.activate(capabilityContext)) }
            ?: ToolExecutionResult(
                content = "$propertyKey not found: $capabilityName",
                isError = true
            )
    }
}