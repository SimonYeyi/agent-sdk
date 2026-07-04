package io.github.yeyi.agent.tool.compression

import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.serialization.TypeToken
import io.github.yeyi.agent.tool.serialization.TypedTool
import kotlinx.serialization.json.JsonElement

/**
 * Compressible Tool — 继承 TypedTool，叠加 schema 压缩和 execution 字符串解析。
 *
 * schema 由 [SignatureGenerator] 根据 serializer 自动生成。
 * execution 字符串由 [CompressTool.extractArguments] 解析。
 *
 * @param P 参数类型（输入）
 * @param R 结果类型（输出）
 */
public abstract class CompressibleTool<P, R>(
    parameterType: TypeToken<P>,
    resultType: TypeToken<R>
) : TypedTool<P, R>(parameterType, resultType) {

    private val signature = SignatureGenerator.generate(parameterType.serializer)

    override val parametersSchema: ToolParameters
        get() = ToolParameters.JsonSchema(signatureToJsonSchema())

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        val originalArgs = CompressTool.extractArguments(arguments, signature)
        return super.execute(originalArgs, context)
    }

    private fun signatureToJsonSchema(): String {
        val params = signature.params.joinToString(", ") { param ->
            val typeStr = formatType(param.type)
            val required = if (param.required) "" else "?"
            val desc = param.description?.let { " | \"$it\"" } ?: ""
            "${param.name}$required: $typeStr$desc"
        }
        return """{"type":"object","properties":{"execution":{"type":"string","description":"$name($params)"}},"required":["execution"]}"""
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
