package io.github.yeyi.agent.tool.serialization

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Typed Tool — 自动完成 JsonElement ↔ typed 对象转换。
 *
 * 子类只需实现 [execute] 方法，业务逻辑完全在 typed 世界。
 * 参数类型和结果类型通过 [TypeToken] 携带 serializer。
 *
 * @param P 参数类型
 * @param R 结果类型
 */
public abstract class TypedTool<P, R>(
    private val parameterType: TypeToken<P>,
    private val resultType: TypeToken<R>
) : Tool {

    abstract override val name: String
    abstract override val description: String

    final override val parametersSchema: ToolParameters =
        ToolParameters.JsonSchema(signatureToJsonSchema())

    /**
     * Tool 接口的 execute 方法。
     * 自动完成 JsonElement → typed → JsonElement 的转换。
     */
    final override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val parameters = Json.decodeFromJsonElement(parameterType.serializer, arguments)
        val result = execute(parameters, context)
        return ToolExecutionResult.success(Json.encodeToString(resultType.serializer, result))
    }

    /**
     * 子类实现业务逻辑。
     *
     * @param parameters 反序列化后的 typed 参数
     * @param context 执行上下文
     * @return typed 结果，会自动序列化为 JSON
     */
    protected abstract suspend fun execute(parameters: P, context: ToolContext): R

    private fun signatureToJsonSchema(): String {
        val descriptor = parameterType.serializer.descriptor
        val properties = (0 until descriptor.elementsCount).mapNotNull { index ->
            val elementName = descriptor.getElementName(index)
            if (elementName.isEmpty()) return@mapNotNull null
            val kind = descriptor.getElementDescriptor(index).kind.toString()
            val descAnnotation = descriptor.getElementAnnotations(index)
                .filterIsInstance<Description>()
                .firstOrNull()
            val descPart = descAnnotation?.let { ""","description":"${it.value}"""" } ?: ""
            """"$elementName":{"type":"${mapKindToType(kind)}"$descPart}"""
        }.joinToString(",")

        return """{"type":"object","properties":{$properties}}"""
    }

    private fun mapKindToType(kind: String): String {
        return when (kind) {
            "STRING" -> "string"
            "BYTE", "SHORT", "INT", "LONG", "FLOAT", "DOUBLE" -> "number"
            "BOOLEAN" -> "boolean"
            "CHAR" -> "string"
            else -> "string"
        }
    }
}

/**
 * TypedTool 工厂方法，通过 inline reified 特性自动获取 serializer。
 *
 * 使用方式：
 * ```kotlin
 * val tool = typedTool<EmailRequest, SendEmailResult>("send_email", "发送邮件") { params, ctx ->
 *     // 业务逻辑
 *     SendEmailResult("msg-123", "2024-01-01")
 * }
 * ```
 */
public inline fun <reified P, reified R> typedTool(
    name: String,
    description: String,
    noinline execute: suspend (P, ToolContext) -> R
): TypedTool<P, R> {
    return object : TypedTool<P, R>(TypeToken(), TypeToken()) {
        override val name: String = name
        override val description: String = description
        override suspend fun execute(parameters: P, context: ToolContext): R =
            execute(parameters, context)
    }
}
