package io.github.yeyi.agent.tool.compression

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.serialization.tool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
            ?.let { compressor.compress(name, it.schema) }
            ?.also { compressionResult = it }
            ?.let { ToolParameters.JsonSchema(it.compressedSchema) }
            ?: delegate.parametersSchema
    }

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val originalArgs = compressionResult?.let {
            val execution = arguments.jsonObject["execution"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing 'execution'")
            parser.parse(execution, it.signature)
        } ?: arguments
        return delegate.execute(originalArgs, context)
    }
}

public inline fun <reified P, reified R> tool(
    name: String,
    description: String,
    compress: Boolean,
    noinline execute: suspend (P, ToolContext) -> R
): Tool {
    val typedTool = tool<P, R>(name, description, execute)
    return if (compress) CompressTool(typedTool) else typedTool
}
