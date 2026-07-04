package io.github.yeyi.agent.tool.compression

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 压缩结果。
 *
 * @param compressedSchema 压缩后的 JSON Schema（给 LLM 用）
 * @param signature 函数签名（用于后续解析 execution）
 */
public data class CompressionResult(
    val compressedSchema: String,
    val signature: FunctionSignature
)

/**
 * Schema 压缩器 — 将 JSON Schema 压缩为函数签名格式。
 */
public interface SchemaCompressor {
    public fun compress(name: String, schema: String): CompressionResult
}

/**
 * 默认压缩器实现。
 */
internal class DefaultSchemaCompressor : SchemaCompressor {
    private val json = Json { ignoreUnknownKeys = true }

    override fun compress(name: String, schema: String): CompressionResult {
        val element = json.parseToJsonElement(schema)
        val signature = compressObject(element, name)
        val compressedSchema = buildCompressedSchema(signature)
        return CompressionResult(compressedSchema, signature)
    }

    private fun compressObject(element: JsonElement, name: String): FunctionSignature {
        if (element !is JsonObject) {
            return FunctionSignature(name, emptyList())
        }

        val properties = element["properties"]?.jsonObject ?: return FunctionSignature(name, emptyList())
        val requiredSet = element["required"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.toSet()
            ?: emptySet()

        val params = properties.mapNotNull { (paramName, schema) ->
            parseParam(paramName, schema, requiredSet.contains(paramName))
        }

        return FunctionSignature(name, params)
    }

    private fun parseParam(name: String, schema: JsonElement, required: Boolean): Param? {
        if (schema !is JsonObject) return null

        val description = schema["description"]?.jsonPrimitive?.content
        val type = parseType(schema)
            ?: return Param(name, ParamType.StringType(), required, description)

        return Param(name, type, required, description)
    }

    private fun parseType(schema: JsonElement): ParamType? {
        if (schema !is JsonObject) return null

        val enumValues = schema["enum"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
        if (enumValues != null) {
            return ParamType.EnumType(enumValues)
        }

        val items = schema["items"]?.jsonObject
        if (items != null) {
            val itemsType = parseSimpleType(items)
            val isArray = schema["type"]?.jsonPrimitive?.content == "array"
            if (isArray) {
                return when (itemsType) {
                    "string" -> ParamType.StringType(isArray = true)
                    "number" -> ParamType.NumberType(isArray = true)
                    "boolean" -> ParamType.BooleanType(isArray = true)
                    "object" -> ParamType.ObjectType(isArray = true)
                    else -> ParamType.StringType(isArray = true)
                }
            }
        }

        val typeStr = parseSimpleType(schema)
        return when (typeStr) {
            "string" -> ParamType.StringType()
            "number" -> ParamType.NumberType()
            "integer" -> ParamType.NumberType()
            "boolean" -> ParamType.BooleanType()
            "object" -> ParamType.ObjectType()
            "array" -> ParamType.StringType(isArray = true)
            else -> null
        }
    }

    private fun parseSimpleType(schema: JsonElement): String? {
        if (schema !is JsonObject) return null
        return schema["type"]?.jsonPrimitive?.content
    }

    private fun buildCompressedSchema(signature: FunctionSignature): String {
        return buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("execution", buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("string"))
                    put("description", kotlinx.serialization.json.JsonPrimitive(formatSignature(signature)))
                })
            })
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("execution")) })
        }.toString()
    }

    private fun formatSignature(signature: FunctionSignature): String {
        val params = signature.params.joinToString(", ") { param ->
            val typeStr = formatType(param.type)
            val required = if (param.required) "" else "?"
            val desc = param.description?.let { " | \"$it\"" } ?: ""
            "${param.name}$required: $typeStr$desc"
        }
        return "${signature.name}($params)"
    }

    private fun formatType(type: ParamType): String {
        return when (type) {
            is ParamType.StringType -> if (type.isArray) "string[]" else "string"
            is ParamType.NumberType -> if (type.isArray) "number[]" else "number"
            is ParamType.BooleanType -> if (type.isArray) "boolean[]" else "boolean"
            is ParamType.ObjectType -> if (type.isArray) "object[]" else "object"
            is ParamType.EnumType -> "enum(${type.values.joinToString(", ")})"
        }
    }
}
