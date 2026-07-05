package io.github.yeyi.agent.tool.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * JSON Schema 生成器 — 将 SerialDescriptor 转换为标准 JSON Schema。
 */
public object SchemaGenerator {

    /**
     * 根据 descriptor 生成 JSON Schema。
     *
     * 支持：
     * - 基本类型（string, number, boolean, enum）
     * - sealed class → oneOf schema
     * - @Description 注解生成字段描述
     */
    @OptIn(ExperimentalSerializationApi::class)
    public fun generateSchema(serializer: KSerializer<*>): String {
        val descriptor = serializer.descriptor
        val properties = (0 until descriptor.elementsCount).mapNotNull { index ->
            val elementName = descriptor.getElementName(index)
            if (elementName.isEmpty()) return@mapNotNull null
            val elementDescriptor = descriptor.getElementDescriptor(index)

            // 检查是否为 sealed class
            if (elementDescriptor.kind == PolymorphicKind.SEALED && elementDescriptor.elementsCount > 0) {
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
