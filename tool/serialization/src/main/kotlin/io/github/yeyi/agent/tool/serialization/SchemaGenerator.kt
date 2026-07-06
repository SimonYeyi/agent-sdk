package io.github.yeyi.agent.tool.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * JSON Schema 生成器 — 将 SerialDescriptor 转换为标准 JSON Schema。
 *
 * 递归处理:任意层级的嵌套 object / list / set / sealed class 都用同一套逻辑,
 * 不区分"顶层"和"嵌套"。每一级都遵循相同的规则:
 * - 必填/可空通过 `required` 数组表达(`isNullable` 为 false 才进入 required)
 * - 字段描述通过 `@Description` 注解生成 description
 * - sealed class 生成 oneOf,每个分支带 `required` 数组
 */
public object SchemaGenerator {

    @OptIn(ExperimentalSerializationApi::class)
    public fun generateSchema(serializer: KSerializer<*>): String {
        return buildObjectSchema(serializer.descriptor, includeRequired = true)
    }

    /**
     * 生成 object schema(STRUCT / CLASS / 顶层)。
     *
     * @param includeRequired 是否在 schema 末尾追加 `required` 数组。
     *   嵌套生成 items / oneOf 分支等"被引用"的 object 时,可以传 false
     *   (调用方负责把它纳入父级 required)。
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun buildObjectSchema(descriptor: SerialDescriptor, includeRequired: Boolean): String {
        val properties = mutableListOf<String>()
        val required = mutableListOf<String>()
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            if (name.isEmpty()) continue
            val elemDesc = descriptor.getElementDescriptor(i)
            val elemAnnos = descriptor.getElementAnnotations(i)
            val isRequired = !elemDesc.isNullable
            properties.add("\"$name\":${buildPropertySchema(elemDesc, elemAnnos, isRequired)}")
            if (isRequired) required.add("\"$name\"")
        }
        val propsStr = properties.joinToString(",")
        val reqStr = if (includeRequired && required.isNotEmpty())
            ",\"required\":[${required.joinToString(",")}]"
        else ""
        return "{\"type\":\"object\",\"properties\":{$propsStr}$reqStr}"
    }

    /**
     * 生成单个属性的 schema 块 `{...}`。
     *
     * 分发逻辑(覆盖所有 SerialKind,任何层级都走这里):
     * - PolymorphicKind.SEALED → oneOf
     * - ENUM → `{"type":"string","enum":[...]}`
     * - OBJECT(Kotlin `object` 声明)→ `{"type":"object"}`
     * - LIST / SET → `{"type":"array","items":<递归>}`
     * - STRUCT / CLASS(嵌套 data class)→ 递归 `buildObjectSchema`
     * - 其他(STRING/NUMBER/BOOLEAN/CHAR/CONTEXTUAL)→ primitive
     *
     * 描述追加在末尾(若有),位置不影响 schema 语义。
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun buildPropertySchema(
        descriptor: SerialDescriptor,
        annotations: List<Annotation>,
        @Suppress("UNUSED_PARAMETER") isRequired: Boolean
    ): String {
        val desc = annotations.filterIsInstance<Description>().firstOrNull()?.value
        val kindStr = descriptor.kind.toString()
        val base = when {
            descriptor.kind == PolymorphicKind.SEALED ->
                buildOneOfSchema(descriptor)

            kindStr == "ENUM" -> {
                val values = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
                "{\"type\":\"string\",\"enum\":[${values.joinToString(",") { "\"$it\"" }}]}"
            }

            kindStr == "OBJECT" ->
                "{\"type\":\"object\"}"

            kindStr == "LIST" || kindStr == "SET" -> {
                val elemDesc = descriptor.getElementDescriptor(0)
                val itemSchema = buildPropertySchema(elemDesc, emptyList(), true)
                "{\"type\":\"array\",\"items\":$itemSchema}"
            }

            kindStr == "STRUCT" || kindStr == "CLASS" ->
                buildObjectSchema(descriptor, includeRequired = true)

            else ->
                "{\"type\":\"${mapKindToType(kindStr)}\"}"
        }
        return appendDescription(base, desc)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun buildOneOfSchema(elementDescriptor: SerialDescriptor): String {
        val discriminatorField = elementDescriptor.annotations
            .filterIsInstance<JsonClassDiscriminator>()
            .firstOrNull()
            ?.discriminator ?: "type"

        // kotlinx.serialization 1.9.0 sealed class 结构:
        // elements[0] = "type" (STRING) — discriminator 字段名
        // elements[1] = "value" (CONTEXTUAL) — 实际子类作为它的 elements
        val valueDescriptor = elementDescriptor.getElementDescriptor(1)
        val subclassCount = valueDescriptor.elementsCount

        val branches = (0 until subclassCount).mapNotNull { subIndex ->
            val subDescriptor = valueDescriptor.getElementDescriptor(subIndex)
            val subName = valueDescriptor.getElementName(subIndex)
            if (subName.isEmpty()) return@mapNotNull null

            val discriminatorValue = subDescriptor.annotations
                .filterIsInstance<SerialName>()
                .firstOrNull()
                ?.value ?: subName

            val properties = mutableListOf<String>()
            val required = mutableListOf<String>()

            properties.add("\"$discriminatorField\":{\"const\":\"$discriminatorValue\"}")
            required.add("\"$discriminatorField\"")

            if (subDescriptor.elementsCount > 0) {
                for (i in 0 until subDescriptor.elementsCount) {
                    val name = subDescriptor.getElementName(i)
                    if (name.isEmpty()) continue
                    val propDesc = subDescriptor.getElementDescriptor(i)
                    val propAnnos = subDescriptor.getElementAnnotations(i)
                    val isRequired = !propDesc.isNullable
                    properties.add("\"$name\":${buildPropertySchema(propDesc, propAnnos, isRequired)}")
                    if (isRequired) required.add("\"$name\"")
                }
            }

            val propsStr = properties.joinToString(",")
            val reqStr = if (required.isNotEmpty())
                ",\"required\":[${required.joinToString(",")}]"
            else ""
            "{\"type\":\"object\",\"properties\":{$propsStr}$reqStr}"
        }

        return "{\"oneOf\":[${branches.joinToString(",")}]}"
    }

    /**
     * 在 JSON 对象末尾注入 `description` 字段(若有)。
     * 假定 `json` 是一个完整的 JSON object,以 `}` 结尾。
     */
    private fun appendDescription(json: String, desc: String?): String {
        if (desc == null) return json
        val last = json.lastIndexOf('}')
        if (last < 0) return json
        return json.substring(0, last) + ",\"description\":\"${escapeJsonString(desc)}\"}" + json.substring(last + 1)
    }

    private fun escapeJsonString(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun mapKindToType(kind: String): String = when (kind) {
        "STRING" -> "string"
        "BYTE", "SHORT", "INT", "LONG", "FLOAT", "DOUBLE" -> "number"
        "BOOLEAN" -> "boolean"
        "CHAR" -> "string"
        // CONTEXTUAL(由 ContextualSerializer 解析的实际类型在 schema 生成时不可知)
        // 降级为 string,让用户用自定义 serializer 覆盖
        else -> "string"
    }
}
