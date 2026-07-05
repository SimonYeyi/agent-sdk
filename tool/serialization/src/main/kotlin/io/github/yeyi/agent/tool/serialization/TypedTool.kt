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

            // 检查是否为 sealed class
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

        // kotlinx.serialization 1.9.0 sealed class 结构：
        // elements[0] = "type" (STRING) -  discriminator 字段名
        // elements[1] = "value" (CONTEXTUAL) - 实际子类在这里，elementsCount = 子类数量
        val valueDescriptor = elementDescriptor.getElementDescriptor(1)
        val subclassCount = valueDescriptor.elementsCount

        // 遍历子类，收集每个分支
        val branches = (0 until subclassCount).mapNotNull { subIndex ->
            val subDescriptor = valueDescriptor.getElementDescriptor(subIndex)
            val subName = valueDescriptor.getElementName(subIndex)
            if (subName.isEmpty()) return@mapNotNull null

            // 获取 discriminator 值 - 有 @SerialName 用它，没有用 simpleName
            val discriminatorValue = subDescriptor.annotations
                .filterIsInstance<SerialName>()
                .firstOrNull()
                ?.value ?: subName

            // 收集所有属性：discriminator 字段 + 子类自身属性
            val allProps = mutableListOf<String>()

            // 首先添加 discriminator 字段作为属性条目
            allProps.add(""""$discriminatorField":{"const":"$discriminatorValue"}""")

            // 添加子类自身的属性
            if (subDescriptor.elementsCount > 0) {
                (0 until subDescriptor.elementsCount).forEach { propIndex ->
                    val propName = subDescriptor.getElementName(propIndex)
                    if (propName.isEmpty()) return@forEach
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
                    allProps.add(""""$propName":{$propTypePart$propDescPart}""")
                }
            }

            // 构建分支：type: object, properties 包含所有字段
            val propsStr = allProps.joinToString(",")
            """{"type":"object","properties":{$propsStr}}"""
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
