package io.github.yeyi.agent.demo.realtime.s2s

import io.github.yeyi.agent.demo.realtime.tool.ContextFreeTool
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.toDefinition
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 将 boss 侧 agent 工具适配为 S2S 实时协议工具（单向转换）。
 *
 * S2S 实时协议的工具执行不携带 agent 运行时上下文，因此仅支持 [ContextFreeTool]
 * 的适配。构造参数直接以 [ContextFreeTool] 类型收口，编译期保证被适配工具具备
 * 无上下文执行能力，不存在运行期适配失败。
 *
 * 声明信息（name/description/parameters）与执行统一转发到被代理的 agent 工具，
 * 不重复定义工具本身。
 */
class S2sToolAdapter(
    private val delegate: ContextFreeTool,
) : io.github.yeyi.agent.realtime.Tool {
    override val name: String get() = delegate.name
    override val description: String get() = delegate.description
    override val parametersSchema: JsonObject = delegate.toDefinition().parametersSchema

    override suspend fun execute(arguments: JsonElement): String =
        delegate.execute(arguments).parts
            .filterIsInstance<ContentPart.Text>()
            .joinToString("\n") { it.text }
}
