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
 * Delegate-mode Tool: one shared Tool for all capabilities in [registry].
 *
 * Schema is `${capabilityName}_name` (the routing field) merged with the
 * Adapter-supplied [arguments] (the per-category call shape).
 * If [arguments] is null (capabilities in this category take no
 * arguments, e.g. a skill-style load), the schema is just the routing field.
 * The LLM-provided `arguments` JSON is forwarded in full to
 * [Capability.activate] so the matched capability can parse what it expects.
 */
internal class CapabilityLoadTool<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>(
    private val registry: CapabilityRegistry<Ctx, C, T>,
    private val capabilityContextFactory: CapabilityContextFactory<Ctx>,
    private val arguments: CapabilityArguments<T>?
) : Tool {
    private val capabilityName = registry.capabilityName
    private val capabilityNameKey = "${capabilityName}_name"

    override val name: String = "load_$capabilityName"

    override val description: String by lazy {
        """
        |当以下 $capabilityName 适用于本次任务时，调用本工具以激活使用：
        |${registry.all().joinToString("\n") { "- ${it.name}：${it.description}" }}
        |如果本次任务需要多个 $capabilityName 并行处理，可以生成多个工具调用。
    """.trimMargin()
    }

    override val parametersSchema: ToolParameters by lazy {
        val properties = buildJsonObject {
            put(capabilityNameKey, buildJsonObject { put("type", "string") })
            if (arguments != null) {
                put("arguments", Json.parseToJsonElement(arguments.schema))
            }
        }
        val required = buildJsonArray {
            add(JsonPrimitive(capabilityNameKey))
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
        val capabilityName = arguments.jsonObject[capabilityNameKey]
            ?.let { (it as? JsonPrimitive)?.content }
            ?: return ToolExecutionResult.error("Missing $capabilityNameKey")

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
            ?: ToolExecutionResult.error("${this.capabilityName} not found: $capabilityName")
    }
}
