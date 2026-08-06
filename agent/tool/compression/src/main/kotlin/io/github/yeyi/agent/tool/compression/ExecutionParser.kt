package io.github.yeyi.agent.tool.compression

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Execution 解析器 — 将 execution 字符串还原为结构化 JSON。
 */
public interface ExecutionParser {
    /**
     * 解析 execution 字符串。
     *
     * @param execution execution 字符串，如 `send_email(to='x@x.com', tags=['work'])`
     * @param signature 函数签名
     * @return 还原后的 JSON 对象
     */
    public fun parse(execution: String, signature: FunctionSignature): JsonElement
}

/**
 * 默认实现 — 使用递归下降 parser 解析 execution 字符串。
 */
internal class DefaultExecutionParser : ExecutionParser {

    override fun parse(execution: String, signature: FunctionSignature): JsonElement {
        return ExecutionStringParser(execution, signature).parse()
    }
}

private class ExecutionStringParser(
    private val input: String,
    private val signature: FunctionSignature
) {
    private var pos = 0
    private val paramMap = signature.params.associateBy { it.name }

    public fun parse(): JsonObject {
        // 解析 function_name(若后面不是 '(' 而是 '='/' ',说明模型只回了参数,无函数名)
        skipWhitespace()
        val savedPos = pos
        val funcName = parseIdentifier()
        skipWhitespace()

        val hasParens = pos < input.length && input[pos] == '('
        if (hasParens) {
            pos++ // 跳过 '('
        } else if (pos < input.length && (input[pos] == '=' || input[pos] == ':')) {
            // 宽容:模型只回了参数(没回函数名,无 `(` 包裹) — 回到起点,直接当参数列表解析
            pos = savedPos
        }
        // 其它情况(既不是 '(' 也不是 '='/':'):保持原行为,让后续循环尽力解析
        skipWhitespace()

        // 模式分发:模型偶尔按 Kotlin-style 输出位置参数(无参数名,按顺序赋值)
        val positional = detectPositionalMode()
            && !signature.isOneOf
            && signature.params.isNotEmpty()
        val args = if (positional) parsePositionalArgs() else parseNamedArgs()

        // 跳过 ')'
        if (pos < input.length && input[pos] == ')') {
            pos++
        }

        return args
    }

    private fun detectPositionalMode(): Boolean {
        skipWhitespace()
        if (pos >= input.length || input[pos] == ')') return false

        val c = input[pos]
        // 明确是字面量 → positional
        if (c == '\'' || c == '"' || c == '[' || c == '{') return true
        if (c.isDigit() || c == '-') return true

        // identifier 起始:扫描首个 token,看后面是否紧跟 '=' 或 ':'
        val saved = pos
        while (pos < input.length && !input[pos].isWhitespace() &&
            input[pos] != '(' && input[pos] != ')' &&
            input[pos] != '=' && input[pos] != ':' && input[pos] != ','
        ) {
            pos++
        }
        skipWhitespace()
        val hasSep = pos < input.length && (input[pos] == '=' || input[pos] == ':')
        pos = saved
        return !hasSep
    }

    private fun parsePositionalArgs(): JsonObject {
        return buildJsonObject {
            var i = 0
            while (pos < input.length && input[pos] != ')') {
                val param = signature.params.getOrNull(i) ?: break
                skipWhitespace()
                val value = parseValue(param.type)
                put(param.name, value)
                i++
                skipWhitespace()
                if (pos < input.length && input[pos] == ',') {
                    pos++
                    skipWhitespace()
                }
            }
        }
    }

    private fun parseNamedArgs(): JsonObject {
        return buildJsonObject {
            while (pos < input.length && input[pos] != ')') {
                val name = parseIdentifier()
                skipWhitespace()

                // 跳过 '=' 或 ':'(: 作为宽容分隔符)
                if (pos < input.length && (input[pos] == '=' || input[pos] == ':')) {
                    pos++
                }
                skipWhitespace()

                // 解析值
                val paramType = paramMap[name]?.type ?: ParamType.StringType()
                val value = parseValue(paramType)

                put(name, value)
                skipWhitespace()

                // 跳过 ','
                if (pos < input.length && input[pos] == ',') {
                    pos++
                    skipWhitespace()
                }
            }
        }
    }

    private fun parseIdentifier(): String {
        skipWhitespace()
        val start = pos
        while (pos < input.length && !input[pos].isWhitespace() &&
            input[pos] != '(' && input[pos] != ')' &&
            input[pos] != '=' && input[pos] != ':' && input[pos] != ','
        ) {
            pos++
        }
        return input.substring(start, pos)
    }

    private fun parseValue(type: ParamType): JsonElement {
        skipWhitespace()
        if (pos >= input.length) return JsonPrimitive("")

        return when (input[pos]) {
            '\'' -> parseStringValue()
            '[' -> parseArrayValue(type)
            '{' -> parseObjectValue(type)
            else -> parseSimpleValue(type)
        }
    }

    private fun parseStringValue(): JsonPrimitive {
        pos++ // skip opening quote
        val sb = StringBuilder()
        while (pos < input.length) {
            when (input[pos]) {
                '\'' -> {
                    pos++ // skip closing quote
                    break
                }

                '\\' -> {
                    pos++ // skip escape char
                    if (pos < input.length) {
                        sb.append(input[pos])
                        pos++
                    }
                }

                else -> {
                    sb.append(input[pos])
                    pos++
                }
            }
        }
        return JsonPrimitive(sb.toString())
    }

    private fun parseArrayValue(type: ParamType): JsonArray {
        val elementType = arrayElementType(type)
        pos++ // skip '['
        val elements = mutableListOf<JsonElement>()
        skipWhitespace()

        while (pos < input.length && input[pos] != ']') {
            elements.add(parseValue(elementType))
            skipWhitespace()
            if (pos < input.length && input[pos] == ',') {
                pos++
                skipWhitespace()
            }
        }

        if (pos < input.length && input[pos] == ']') {
            pos++
        }

        return JsonArray(elements)
    }

    private fun arrayElementType(type: ParamType): ParamType = when (type) {
        is ParamType.StringType -> ParamType.StringType()
        is ParamType.NumberType -> ParamType.NumberType()
        is ParamType.BooleanType -> ParamType.BooleanType()
        is ParamType.ObjectType -> type.copy(isArray = false)
        is ParamType.OneOfType -> type.copy(isArray = false)
        is ParamType.EnumType -> type
    }

    private fun parseObjectValue(type: ParamType): JsonObject {
        val objectType = type as? ParamType.ObjectType
        val oneOfType = type as? ParamType.OneOfType

        val baseFields = objectType?.fields ?: emptyList()
        val fieldMap = baseFields.associateBy { it.name }

        // oneOf 判别字段名(假设所有分支用同一判别字段,取第一个非空 condition 的字段名)
        val discriminatorName = oneOfType?.branches
            ?.firstOrNull { it.condition.isNotEmpty() }
            ?.condition
            ?.substringBefore("=")
            ?.takeIf { it.isNotEmpty() }

        pos++ // skip '{'
        skipWhitespace()

        // positional gate:仅在结构化 object 且非 oneOf 时生效(oneOf positional 暂未支持)
        val positional = oneOfType == null
            && baseFields.isNotEmpty()
            && detectPositionalMode()

        val result = if (positional) {
            parseObjectPositional(baseFields)
        } else {
            parseObjectNamed(oneOfType, fieldMap, discriminatorName)
        }

        if (pos < input.length && input[pos] == '}') {
            pos++
        }
        return JsonObject(result)
    }

    private fun parseObjectPositional(fields: List<Param>): MutableMap<String, JsonElement> {
        val result = mutableMapOf<String, JsonElement>()
        var i = 0
        while (pos < input.length && input[pos] != '}') {
            val field = fields.getOrNull(i) ?: break
            skipWhitespace()
            result[field.name] = parseValue(field.type)
            i++
            skipWhitespace()
            if (pos < input.length && input[pos] == ',') {
                pos++
                skipWhitespace()
            }
        }
        return result
    }

    private fun parseObjectNamed(
        oneOfType: ParamType.OneOfType?,
        fieldMap: Map<String, Param>,
        discriminatorName: String?
    ): MutableMap<String, JsonElement> {
        val result = mutableMapOf<String, JsonElement>()
        var activeBranch: Branch? = null

        while (pos < input.length && input[pos] != '}') {
            val name = parseIdentifier()
            skipWhitespace()
            if (pos < input.length && (input[pos] == '=' || input[pos] == ':')) {
                pos++
            }
            skipWhitespace()

            // oneOf 判别:读到判别字段时匹配分支,后续字段用分支的 fields
            if (oneOfType != null && discriminatorName != null && name == discriminatorName) {
                val rawValue = parseSimpleValue(ParamType.StringType())
                val rawString = (rawValue as? JsonPrimitive)?.content ?: rawValue.toString()
                activeBranch = oneOfType.branches.find { it.condition == "$name=$rawString" }
                    ?: oneOfType.branches.firstOrNull { it.condition.isEmpty() }
                result[name] = rawValue
            } else {
                val fieldType = activeBranch?.params?.find { it.name == name }?.type
                    ?: fieldMap[name]?.type
                    ?: ParamType.StringType()
                result[name] = parseValue(fieldType)
            }

            skipWhitespace()
            if (pos < input.length && input[pos] == ',') {
                pos++
                skipWhitespace()
            }
        }
        return result
    }

    private fun parseSimpleValue(type: ParamType): JsonElement {
        val sb = StringBuilder()
        while (pos < input.length &&
            !input[pos].isWhitespace() &&
            input[pos] != ',' && input[pos] != ')' &&
            input[pos] != '}' && input[pos] != ']'
        ) {
            sb.append(input[pos])
            pos++
        }
        var value = sb.toString().trim()

        // 去掉两侧的引号（单引号或双引号）
        if ((value.startsWith("'") && value.endsWith("'")) ||
            (value.startsWith("\"") && value.endsWith("\""))) {
            value = value.substring(1, value.length - 1)
        }

        return when (type) {
            is ParamType.NumberType -> {
                value.toDoubleOrNull()?.let { JsonPrimitive(it) }
                    ?: JsonPrimitive(value)
            }

            is ParamType.BooleanType -> {
                when (value.lowercase()) {
                    "true" -> JsonPrimitive(true)
                    "false" -> JsonPrimitive(false)
                    else -> JsonPrimitive(value)
                }
            }

            is ParamType.EnumType -> JsonPrimitive(value)
            else -> JsonPrimitive(value)
        }
    }

    private fun skipWhitespace() {
        while (pos < input.length && input[pos].isWhitespace()) {
            pos++
        }
    }
}
