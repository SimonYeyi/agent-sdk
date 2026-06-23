package io.github.yeyi.agent.capability

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Delegate-mode Tool: one shared Tool for all capabilities in [registry].
 *
 * Schema is `${capabilityName}_name` (the routing field) merged with the
 * Adapter-supplied [argumentsSchema] (the per-category call shape).
 * If [argumentsSchema] is null (capabilities in this category take no
 * arguments, e.g. a skill-style load), the schema is just the routing field.
 * The LLM-provided `arguments` JSON is forwarded in full to
 * [Capability.activate] so the matched capability can parse what it expects.
 */
internal class LoadCapabilityTool<Ctx : CapabilityContext, C : Capability<Ctx>>(
    private val registry: CapabilityRegistry<Ctx, C>,
    private val capabilityContextFactory: CapabilityContextFactory<Ctx>,
    private val argumentsSchema: ToolParameters = ToolParameters.Empty,
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

    override val parametersSchema: ToolParameters by lazy {
        when (argumentsSchema) {
            ToolParameters.Empty -> ToolParameters.JsonSchema(
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

            is ToolParameters.JsonSchema -> mergeArgumentsSchema(argumentsSchema.schema)
        }
    }

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
            ?.let { ToolExecutionResult(it.activate(arguments, capabilityContext)) }
            ?: ToolExecutionResult(
                content = "$propertyKey not found: $capabilityName",
                isError = true
            )
    }

    private fun mergeArgumentsSchema(capabilitySchema: String): ToolParameters.JsonSchema {
        val capJson = Json.parseToJsonElement(capabilitySchema).jsonObject
        val capProperties = capJson["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val capRequired = capJson["required"]?.jsonArray ?: JsonArray(emptyList())

        val merged = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put(propertyKey, buildJsonObject { put("type", "string") })
                capProperties.forEach { (k, v) -> put(k, v) }
            })
            put("required", buildJsonArray {
                add(JsonPrimitive(propertyKey))
                capRequired.forEach { add(it) }
            })
        }
        return ToolParameters.JsonSchema(Json.encodeToString(JsonObject.serializer(), merged))
    }
}
