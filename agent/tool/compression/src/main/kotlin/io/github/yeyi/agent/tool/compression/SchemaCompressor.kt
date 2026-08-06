package io.github.yeyi.agent.tool.compression

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

        // 检查是否有 oneOf / anyOf(都按多分支处理)
        val oneOfBranches = element["oneOf"]?.jsonArray
            ?: element["anyOf"]?.jsonArray
        if (oneOfBranches != null) {
            val branches = oneOfBranches.mapNotNull { branch -> parseBranch(branch.jsonObject) }
            if (branches.isNotEmpty()) {
                return FunctionSignature(name, emptyList(), branches)
            }
        }

        // allOf 合并成单个 object schema
        val allOf = element["allOf"]?.jsonArray
        if (allOf != null) {
            val synthetic = mergeAllOf(allOf)
            if (synthetic != null) {
                return compressObject(synthetic, name)
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
        val branchDesc = branch["description"]?.jsonPrimitive?.content

        // 找判别字段:优先 const,回退单值 enum(等价 const)。condition 为空表示 catch-all
        var condition = ""
        var discriminatorFound = false
        for ((fieldName, schema) in properties.entries) {
            val schemaObj = schema.jsonObject
            val constValue = schemaObj["const"]?.jsonPrimitive?.content
            if (constValue != null) {
                condition = "$fieldName=$constValue"
                discriminatorFound = true
                break
            }
            val enumValues = schemaObj["enum"]?.jsonArray?.map { it.jsonPrimitive.content }
            if (enumValues != null && enumValues.size == 1) {
                condition = "$fieldName=${enumValues[0]}"
                discriminatorFound = true
                break
            }
        }
        // 无判别字段也保留(catch-all),用空 condition 标识

        // 过滤:排除 const 字段和单值 enum(它们被当 discriminator,不再作为普通参数)
        val params = properties.entries
            .filter { (_, schema) ->
                val schemaObj = schema.jsonObject
                val isSingleEnum = schemaObj["enum"]?.jsonArray?.let { it.size == 1 } == true
                schemaObj["const"] == null && !isSingleEnum
            }
            .mapNotNull { (paramName, schema) ->
                parseParam(paramName, schema, requiredSet.contains(paramName))
            }

        return Branch(condition, params, branchDesc)
    }

    private fun parseParam(name: String, schema: JsonElement, required: Boolean): Param? {
        if (schema !is JsonObject) return null

        val description = schema["description"]?.jsonPrimitive?.content
        val type = parseType(schema)
            ?: return Param(name, ParamType.StringType(), required, description)

        // type 为 object/oneOf 时,描述的去重规则:
        // - 如果字段 description 和 type description 相同(常见:field 本身是 object),让 type 独占
        // - 如果不同(常见:array of object,字段 desc 是数组的、type desc 是 items 的),两者并存
        // - 如果 type 无 description,字段 description 必须保留
        val typeDesc = when (type) {
            is ParamType.ObjectType -> type.description
            is ParamType.OneOfType -> type.description
            else -> null
        }
        val paramDesc = if (typeDesc != null && typeDesc == description) null else description
        return Param(name, type, required, paramDesc)
    }

    private fun parseType(schema: JsonElement): ParamType? {
        if (schema !is JsonObject) return null

        // 内层 schema 的 description 透传到 OneOfType/ObjectType,供嵌套场景渲染
        val innerDesc = schema["description"]?.jsonPrimitive?.content

        // oneOf / anyOf / allOf 优先级最高(覆盖 type)—— 让判别式字段能嵌套在任意位置
        schema["oneOf"]?.jsonArray?.let { oneOf ->
            val branches = oneOf.mapNotNull { parseBranch(it.jsonObject) }
            if (branches.isNotEmpty()) return ParamType.OneOfType(branches, description = innerDesc)
        }
        schema["anyOf"]?.jsonArray?.let { anyOf ->
            val branches = anyOf.mapNotNull { parseBranch(it.jsonObject) }
            if (branches.isNotEmpty()) return ParamType.OneOfType(branches, description = innerDesc)
        }
        schema["allOf"]?.jsonArray?.let { allOf ->
            return parseAllOf(allOf, innerDesc)
        }

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
                    is ParamType.OneOfType -> itemType.copy(isArray = true)
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
            "object" -> ParamType.ObjectType(fields = parseObjectFields(schema), description = innerDesc)
            "array" -> ParamType.StringType(isArray = true)
            else -> null
        }
    }

    private fun parseAllOf(allOf: JsonArray, description: String? = null): ParamType? {
        val synthetic = mergeAllOf(allOf) ?: return ParamType.ObjectType(description = description)
        return ParamType.ObjectType(fields = parseObjectFields(synthetic), description = description)
    }

    /**
     * 把 allOf 多个分支的 properties 合并、required 取并集,合成一个 object schema。
     * 同名字段取第一个分支(后续分支不覆盖),不解析 $ref。
     * 合并结果为空时返回 null(让调用方决定如何降级)。
     */
    private fun mergeAllOf(allOf: JsonArray): JsonObject? {
        val merged = mutableMapOf<String, JsonElement>()
        val required = mutableSetOf<String>()
        for (branch in allOf) {
            val props = branch.jsonObject["properties"]?.jsonObject
            props?.forEach { (name, schema) ->
                if (name !in merged) merged[name] = schema
            }
            branch.jsonObject["required"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.forEach { required.add(it) }
        }
        if (merged.isEmpty()) return null
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", JsonObject(merged))
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
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
            // oneOf 模式:action=play, song: string; action=pause; action=volume, volume: number
            // catch-all 分支(空 condition)用 * 前缀标识
            signature.branches.joinToString("; ") { branch ->
                val conditionParam = branch.condition
                val branchParams = branch.params.joinToString(", ") { param ->
                    val typeStr = formatType(param.type)
                    val required = if (param.required) "" else "?"
                    val desc = param.description?.let { " | \"$it\"" } ?: ""
                    "${param.name}$required: $typeStr$desc"
                }
                val branchDesc = branch.description?.let { " | \"$it\"" } ?: ""
                when {
                    branchParams.isEmpty() -> (conditionParam.ifEmpty { "*" }) + branchDesc
                    conditionParam.isEmpty() -> "* $branchParams$branchDesc"
                    else -> "$conditionParam, $branchParams$branchDesc"
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
            is ParamType.OneOfType -> formatOneOfType(type)
        }
    }

    private fun formatObjectType(type: ParamType.ObjectType): String {
        if (type.fields.isEmpty()) {
            val bare = if (type.isArray) "object[]" else "object"
            return bare + (type.description?.let { " | \"$it\"" } ?: "")
        }
        val fieldsStr = type.fields.joinToString(", ") { param ->
            val typeStr = formatType(param.type)
            val required = if (param.required) "" else "?"
            val desc = param.description?.let { " | \"$it\"" } ?: ""
            "${param.name}$required: $typeStr$desc"
        }
        val rendered = if (type.isArray) "[{$fieldsStr}]" else "{$fieldsStr}"
        val wrapperDesc = type.description?.let { " | \"$it\"" } ?: ""
        return rendered + wrapperDesc
    }

    private fun formatOneOfType(type: ParamType.OneOfType): String {
        // 用 ; 分隔分支,跟顶层 oneOf 一致( | 已被描述字段占用)
        val branchesStr = type.branches.joinToString("; ") { branch -> formatBranchBody(branch) }
        val rendered = if (type.isArray) "[$branchesStr]" else branchesStr
        val wrapperDesc = type.description?.let { " | \"$it\"" } ?: ""
        return rendered + wrapperDesc
    }

    private fun formatBranchBody(branch: Branch): String {
        val condition = branch.condition
        val paramsStr = branch.params.joinToString(", ") { param ->
            val typeStr = formatType(param.type)
            val required = if (param.required) "" else "?"
            val desc = param.description?.let { " | \"$it\"" } ?: ""
            "${param.name}$required: $typeStr$desc"
        }
        val body = paramsStr.ifEmpty { "" }
        val rendered = if (condition.isEmpty()) {
            "{* $body}".trim()  // catch-all 分支用 * 标识
        } else {
            "{$condition, $body}"
        }
        return rendered + (branch.description?.let { " | \"$it\"" } ?: "")
    }
}
