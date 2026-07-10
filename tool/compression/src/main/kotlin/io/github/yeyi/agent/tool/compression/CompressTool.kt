package io.github.yeyi.agent.tool.compression

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 压缩工具 — 装饰原始 Tool，将其 parametersSchema 替换为 execution 格式，
 * 并在 execute 时将 execution 字符串还原为原始参数。
 */
public class CompressTool(private val delegate: Tool) : Tool {
    private val compressor: SchemaCompressor = DefaultSchemaCompressor()
    private val parser: ExecutionParser = DefaultExecutionParser()
    private var compressionResult: CompressionResult? = null
    override val name: String = delegate.name
    override val description: String = delegate.description

    override val parametersSchema: ToolParameters by lazy {
        (delegate.parametersSchema as? ToolParameters.JsonSchema)
            ?.let { original ->
                compressor.compress(name, original.schema)
                    .takeIf { it.compressedSchema.length < original.schema.length }
            }
            ?.also { compressionResult = it }
            ?.let { ToolParameters.JsonSchema(it.compressedSchema) }
            ?: delegate.parametersSchema
    }

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val originalArgs = compressionResult?.let {
            val execution = arguments.jsonObject["execution"]
                ?: throw IllegalArgumentException("Missing 'execution'")
            // 有时模型不准从约定，依然通过 jsonObject 返回
            if (execution is JsonPrimitive && arguments.jsonObject.size == 1) {
                parser.parse(execution.content, it.signature)
            } else {
                // {"execution":{"city":"背景","time":"today"}}
                // {"execution":"get_weather","city":"背景","time":"today"}
                execution as? JsonObject
            }
        } ?: arguments
        return delegate.execute(originalArgs, context)
    }
}

