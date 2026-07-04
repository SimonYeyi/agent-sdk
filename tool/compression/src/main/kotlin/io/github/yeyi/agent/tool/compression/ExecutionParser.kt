package io.github.yeyi.agent.tool.compression

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

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
    private val parserJson = Json { ignoreUnknownKeys = true }

    public fun parse(): JsonObject {
        // 解析 function_name
        val funcName = parseIdentifier()
        skipWhitespace()

        // 跳过 '('
        if (pos < input.length && input[pos] == '(') {
            pos++
        }
        skipWhitespace()

        // 解析参数
        val args = buildJsonObject {
            while (pos < input.length && input[pos] != ')') {
                val name = parseIdentifier()
                skipWhitespace()

                // 跳过 '='
                if (pos < input.length && input[pos] == '=') {
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

        // 跳过 ')'
        if (pos < input.length && input[pos] == ')') {
            pos++
        }

        return args
    }

    private fun parseIdentifier(): String {
        skipWhitespace()
        val start = pos
        while (pos < input.length && !input[pos].isWhitespace() && input[pos] != '(' && input[pos] != ')' && input[pos] != '=' && input[pos] != ',') {
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
            '{' -> parseObjectValue()
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
        pos++ // skip '['
        val elements = mutableListOf<JsonElement>()
        skipWhitespace()

        while (pos < input.length && input[pos] != ']') {
            val elementType = when (type) {
                is ParamType.StringType -> ParamType.StringType()
                is ParamType.NumberType -> ParamType.NumberType()
                is ParamType.BooleanType -> ParamType.BooleanType()
                is ParamType.ObjectType -> ParamType.ObjectType()
                is ParamType.EnumType -> ParamType.StringType()
            }
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

    private fun parseObjectValue(): JsonObject {
        pos++ // skip '{'
        val sb = StringBuilder()
        var braceCount = 1

        while (pos < input.length && braceCount > 0) {
            when (input[pos]) {
                '{' -> {
                    braceCount++
                    sb.append(input[pos])
                    pos++
                }

                '}' -> {
                    braceCount--
                    if (braceCount > 0) {
                        sb.append(input[pos])
                    }
                    pos++
                }

                '\'' -> {
                    sb.append(input[pos])
                    pos++
                    while (pos < input.length && input[pos] != '\'') {
                        if (input[pos] == '\\' && pos + 1 < input.length) {
                            sb.append(input[pos])
                            pos++
                        }
                        sb.append(input[pos])
                        pos++
                    }
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

        val objStr = sb.toString().trim()
        if (objStr.isEmpty()) return JsonObject(emptyMap())

        return try {
            parserJson.parseToJsonElement("{$objStr}").jsonObject
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
    }

    private fun parseSimpleValue(type: ParamType): JsonElement {
        val sb = StringBuilder()
        while (pos < input.length && !input[pos].isWhitespace() && input[pos] != ',' && input[pos] != ')') {
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
