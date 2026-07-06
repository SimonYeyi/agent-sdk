package io.github.yeyi.agent.tool.compression

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; allowTrailingComma = true }

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

        // 检查是否有 oneOf
        val oneOf = element["oneOf"]?.jsonArray
        if (oneOf != null) {
            val branches = oneOf.mapNotNull { branch -> parseBranch(branch.jsonObject) }
            if (branches.isNotEmpty()) {
                return FunctionSignature(name, emptyList(), branches)
            }
        }

        val properties = element["properties"]?.jsonObject
            ?: return FunctionSignature(name, emptyList())
        val requiredSet = element["required"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.toSet()
            ?: emptySet()

        val params = properties.mapNotNull { (paramName, schema) ->
            parseParam(paramName, schema, requiredSet.contains(paramName))
        }

        return FunctionSignature(name, params)
    }

    private fun parseBranch(branch: JsonObject): Branch? {
        val properties = branch["properties"]?.jsonObject ?: return null
        val requiredSet = branch["required"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.toSet()
            ?: emptySet()

        // 找出 const 字段作为条件
        var condition: String? = null
        for ((fieldName, schema) in properties.entries) {
            val constValue = schema.jsonObject["const"]?.jsonPrimitive?.content
            if (constValue != null) {
                condition = "$fieldName=$constValue"
                break
            }
        }
        if (condition == null) return null

        val params = properties.entries
            .filter { (_, schema) ->
                // 排除 const 字段本身（它用于条件，不作为参数）
                schema.jsonObject["const"] == null
            }
            .mapNotNull { (paramName, schema) ->
                parseParam(paramName, schema, requiredSet.contains(paramName))
            }

        return Branch(condition, params)
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
            val isArray = schema["type"]?.jsonPrimitive?.content == "array"
            if (isArray) {
                val itemType = parseType(items) ?: return ParamType.StringType(isArray = true)
                return when (itemType) {
                    is ParamType.StringType -> ParamType.StringType(isArray = true)
                    is ParamType.NumberType -> ParamType.NumberType(isArray = true)
                    is ParamType.BooleanType -> ParamType.BooleanType(isArray = true)
                    is ParamType.ObjectType -> itemType.copy(isArray = true)
                    is ParamType.EnumType -> ParamType.StringType(isArray = true)
                }
            }
        }

        val typeStr = parseSimpleType(schema)
        return when (typeStr) {
            "string" -> ParamType.StringType()
            "number" -> ParamType.NumberType()
            "integer" -> ParamType.NumberType()
            "boolean" -> ParamType.BooleanType()
            "object" -> ParamType.ObjectType(fields = parseObjectFields(schema))
            "array" -> ParamType.StringType(isArray = true)
            else -> null
        }
    }

    private fun parseObjectFields(schema: JsonObject): List<Param> {
        val properties = schema["properties"]?.jsonObject ?: return emptyList()
        val requiredSet = schema["required"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.toSet()
            ?: emptySet()
        return properties.mapNotNull { (paramName, propSchema) ->
            parseParam(paramName, propSchema, requiredSet.contains(paramName))
        }
    }

    private fun parseSimpleType(schema: JsonElement): String? {
        if (schema !is JsonObject) return null
        return schema["type"]?.jsonPrimitive?.content
    }

    private fun buildCompressedSchema(signature: FunctionSignature): String {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("execution", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive(formatSignature(signature)))
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("execution")) })
        }.toString()
    }

    private fun formatSignature(signature: FunctionSignature): String {
        val paramsStr = if (signature.isOneOf) {
            // oneOf 模式：action=play, song: string; action=pause; action=volume, volume: number
            signature.branches.joinToString("; ") { branch ->
                // 条件字段作为第一个"参数"，格式为 field=value
                val conditionParam = branch.condition
                val branchParams = branch.params.joinToString(", ") { param ->
                    val typeStr = formatType(param.type)
                    val required = if (param.required) "" else "?"
                    val desc = param.description?.let { " | \"$it\"" } ?: ""
                    "${param.name}$required: $typeStr$desc"
                }
                if (branchParams.isEmpty()) {
                    conditionParam
                } else {
                    "$conditionParam, $branchParams"
                }
            }
        } else {
            // 普通模式
            signature.params.joinToString(", ") { param ->
                val typeStr = formatType(param.type)
                val required = if (param.required) "" else "?"
                val desc = param.description?.let { " | \"$it\"" } ?: ""
                "${param.name}$required: $typeStr$desc"
            }
        }
        return "${signature.name}($paramsStr)"
    }

    private fun formatType(type: ParamType): String {
        return when (type) {
            is ParamType.StringType -> if (type.isArray) "string[]" else "string"
            is ParamType.NumberType -> if (type.isArray) "number[]" else "number"
            is ParamType.BooleanType -> if (type.isArray) "boolean[]" else "boolean"
            is ParamType.EnumType -> "enum(${type.values.joinToString(", ")})"
            is ParamType.ObjectType -> formatObjectType(type)
        }
    }

    private fun formatObjectType(type: ParamType.ObjectType): String {
        if (type.fields.isEmpty()) {
            return if (type.isArray) "object[]" else "object"
        }
        val fieldsStr = type.fields.joinToString(", ") { param ->
            val typeStr = formatType(param.type)
            val required = if (param.required) "" else "?"
            "${param.name}$required: $typeStr"
        }
        return if (type.isArray) "[{$fieldsStr}]" else "{$fieldsStr}"
    }
}
