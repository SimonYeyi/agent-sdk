package io.github.yeyi.agent.tool.serialization

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Typed Tool — 自动完成 JsonElement ↔ typed 对象转换。
 *
 * 子类只需实现 [execute] 方法，业务逻辑完全在 typed 世界。
 * 参数类型和结果类型通过 [TypeToken] 携带 serializer。
 *
 * 支持 sealed class 属性自动生成 oneOf schema。
 *
 * @param P 参数类型
 * @param R 结果类型
 */
public abstract class TypedTool<P : @Serializable Any, R : @Serializable Any>(
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
        if (result is ToolExecutionResult) return result
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
            val elementDescriptor = descriptor.getElementDescriptor(index)

            // 检查是否为 sealed class（通过 elementsCount > 0 判断）
            if (elementDescriptor.kind == PolymorphicKind.SEALED && elementDescriptor.elementsCount > 0) {
                // oneOf 直接返回，不包装外层
                return@mapNotNull """"$elementName":${generateOneOfSchema(elementDescriptor)}"""
            }

            val kind = elementDescriptor.kind.toString()
            val descAnnotation = descriptor.getElementAnnotations(index)
                .filterIsInstance<Description>()
                .firstOrNull()
            val typePart = if (kind == "ENUM") {
                val enumValues = (0 until elementDescriptor.elementsCount).map {
                    elementDescriptor.getElementName(it)
                }
                """"type":"string","enum":[${enumValues.joinToString(",") { "\"$it\"" }}]"""
            } else {
                """"type":"${mapKindToType(kind)}""""
            }
            val descPart = descAnnotation?.let { ""","description":"${it.value}"""" } ?: ""
            """"$elementName":{$typePart$descPart}"""
        }.joinToString(",")

        return """{"type":"object","properties":{$properties}}"""
    }

    private fun generateOneOfSchema(elementDescriptor: SerialDescriptor): String {
        // discriminator 字段名默认为 "type"，暂不支持自定义
        val discriminatorField = "type"

        // 遍历子类，收集每个分支
        val branches = (0 until elementDescriptor.elementsCount).mapNotNull { subIndex ->
            val subDescriptor: SerialDescriptor
            val subName: String
            val discriminatorValue: String

            try {
                subDescriptor = elementDescriptor.getElementDescriptor(subIndex)
                subName = elementDescriptor.getElementName(subIndex)
                if (subName.isEmpty()) return@mapNotNull null

                // 获取子类名（用于 discriminator 值）- 有 @SerialName 用它，没有用 simpleName
                discriminatorValue = subDescriptor.annotations
                    .filterIsInstance<SerialName>()
                    .firstOrNull()
                    ?.value ?: subName
            } catch (e: IllegalStateException) {
                // primitive descriptor（如 object Stop）获取 annotations 会失败
                return@mapNotNull null
            }

            // 收集子类的属性（排除 discriminator 字段本身）
            val subProperties = try {
                (0 until subDescriptor.elementsCount).mapNotNull { propIndex ->
                    val propName = subDescriptor.getElementName(propIndex)
                    if (propName.isEmpty()) return@mapNotNull null
                    val propDescriptor = subDescriptor.getElementDescriptor(propIndex)
                    val propKind = propDescriptor.kind.toString()
                    val propDesc = subDescriptor.getElementAnnotations(propIndex)
                        .filterIsInstance<Description>()
                        .firstOrNull()
                    val propTypePart = if (propKind == "ENUM") {
                        val enumValues = (0 until propDescriptor.elementsCount).map {
                            propDescriptor.getElementName(it)
                        }
                        """"type":"string","enum":[${enumValues.joinToString(",") { "\"$it\"" }}]"""
                    } else {
                        """"type":"${mapKindToType(propKind)}""""
                    }
                    val propDescPart = propDesc?.let { ""","description":"${it.value}"""" } ?: ""
                    """"$propName":{$propTypePart$propDescPart}"""
                }.joinToString(",")
            } catch (e: IllegalStateException) {
                // primitive descriptor 没有 elements
                ""
            }

            // 构建带 const 约束的分支
            if (subProperties.isEmpty()) {
                """{"$discriminatorField":{"const":"$discriminatorValue"}}"""
            } else {
                """{"$discriminatorField":{"const":"$discriminatorValue"},"type":"object","properties":{$subProperties}}"""
            }
        }

        return """{"oneOf":[${branches.joinToString(",")}]}"""
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
 * val tool = tool<EmailRequest, SendEmailResult>("send_email", "发送邮件") { params, ctx ->
 *     // 业务逻辑
 *     SendEmailResult("msg-123", "2024-01-01")
 * }
 * ```
 */
public inline fun <reified P : @Serializable Any, reified R : @Serializable Any> tool(
    name: String,
    description: String,
    noinline execute: suspend (P, ToolContext) -> R
): Tool {
    return object : TypedTool<P, R>(TypeToken(), TypeToken()) {
        override val name: String = name
        override val description: String = description
        override suspend fun execute(parameters: P, context: ToolContext): R =
            execute(parameters, context)
    }
}

/**
 * 便捷方法，结果类型固定为 String。
 */
@JvmName("toolAsText")
public inline fun <reified P : @Serializable Any> tool(
    name: String,
    description: String,
    noinline execute: suspend (P, ToolContext) -> String
): Tool = tool<P, String>(name, description, execute)

/**
 * 便捷方法，无参数且结果类型固定为 String。
 */
@JvmName("toolAsTextNoArg")
public fun tool(
    name: String,
    description: String,
    execute: suspend (Unit, ToolContext) -> String
): Tool = tool<Unit>(name, description, execute)
