package io.github.yeyi.agent.capability

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Delegate-mode Tool: exposes all capabilities in [registry] through one shared Tool.
 *
 * The schema always contains `name` for routing to a registered capability. When
 * [arguments] is present, the schema also contains a nested `arguments` object matching
 * the capability category's call shape. The selected capability's arguments are decoded
 * with [CapabilityArguments.serializer] before being passed to [Capability.activate].
 */
internal class CapabilityLoadTool<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>(
    private val registry: CapabilityRegistry<C, T, Ctx>,
    private val capabilityContextFactory: CapabilityContextFactory<Ctx>,
    private val arguments: CapabilityArguments<T>?
) : Tool {
    private val capabilityType = registry.capabilityType

    override val name: String = "load_$capabilityType"

    override val description: String by lazy {
        """
        |当以下 $capabilityType 适用于本次任务时，调用本工具以激活使用：
        |${registry.all().joinToString("\n") { "- ${it.name}：${it.description}" }}
        |如果本次任务需要多个 $capabilityType 并行处理，可以生成多个工具调用。
    """.trimMargin()
    }

    override val parametersSchema: ToolParameters by lazy {
        val properties = buildJsonObject {
            put("name", buildJsonObject { put("type", "string") })
            if (arguments != null) {
                put("arguments", Json.parseToJsonElement(arguments.schema))
            }
        }
        val required = buildJsonArray {
            add(JsonPrimitive("name"))
            if (arguments != null) add(JsonPrimitive("arguments"))
        }
        ToolParameters.JsonSchema(
            Json.encodeToString(buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put("required", required)
            })
        )
    }

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val capabilityName = arguments.jsonObject["name"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: return ToolExecutionResult.error("Missing 'name'")

        return registry.all().find { it.name == capabilityName }
            ?.let { capability ->
                val capabilityContext = capabilityContextFactory.create(context)
                val input = this.arguments?.let {
                    Json.decodeFromJsonElement(
                        it.serializer,
                        arguments.jsonObject["arguments"]!!
                    )
                }
                ToolExecutionResult(capability.activate(input, capabilityContext))
            }
            ?: ToolExecutionResult.error("${this.capabilityType} not found: $capabilityName")
    }
}
