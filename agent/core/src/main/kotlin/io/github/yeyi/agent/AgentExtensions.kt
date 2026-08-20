package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.llm.ToolDefinition
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val EMPTY_SCHEMA = Json.parseToJsonElement("""{"type":"object","properties":{}}""")

/**
 * 将当前 [Tool] 转换为 [ToolDefinition]。
 *
 * [Tool.parametersSchema] 为 [ToolParameters.Empty] 时返回空对象 schema，
 * 为 [ToolParameters.JsonSchema] 时解析为 [kotlinx.serialization.json.JsonObject]。
 *
 * @return 可用于 LLM schema 渲染的结构化工具定义。
 */
public fun Tool.toDefinition(): ToolDefinition {
    val schema = when (val ps = parametersSchema) {
        is ToolParameters.Empty -> EMPTY_SCHEMA
        is ToolParameters.JsonSchema -> Json.parseToJsonElement(ps.schema)
    }
    return ToolDefinition(name, description, schema.jsonObject)
}

/**
 * 等待并返回 agent 运行的最终结果。
 *
 * 终端事件识别:
 * - [AgentEvent.Final] → 返回其 [AgentEvent.Final.result]
 * - [AgentEvent.Failed] → 抛出其 [AgentEvent.Failed.cause]（原始异常，不重新包装）
 *
 * 其他 [AgentEvent] 子类型被忽略。
 * Flow 自身异常按 Flow 协议传播。
 */
public suspend fun Flow<AgentEvent>.awaitResult(): AgentResult {
    val terminal = filter { it is AgentEvent.Final || it is AgentEvent.Failed }.first()
    return when (terminal) {
        is AgentEvent.Final -> terminal.result
        is AgentEvent.Failed -> throw terminal.cause
        else -> error("unreachable: filter restricts to Final|Failed, got ${terminal::class.simpleName}")
    }
}

/**
 * 把消息转成给 LLM 看的形态:非当前 turn 的 media part 一律用占位 Text 替换,
 * 避免旧轮次的图片/音频/视频每轮重传,造成 token 膨胀。
 *
 * - [ChatMessage.User] / [ChatMessage.ToolResult]:把非 Text part 转成 Text(占位)
 * - 其他类型([ChatMessage.System] / [ChatMessage.Assistant])已经是文本,直接返回
 */
public fun ChatMessage.toTextMessage(): ChatMessage {
    fun describeMediaSource(source: MediaSource): String = when (source) {
        is MediaSource.Http -> source.url.substringAfterLast('/')
            .ifEmpty { source.url.take(64) }

        is MediaSource.Data -> "inline ${source.base64.length * 3 / 4 / 1024}KB"
        is MediaSource.FileId -> "file:${source.id.take(8)}"
        is MediaSource.Local -> "local fileId=${source.fileId.take(8)}..."
    }

    fun mediaPlaceholder(part: ContentPart): String = when (part) {
        is ContentPart.Text -> part.text
        is ContentPart.Image -> "[image] ${describeMediaSource(part.source)}"
        is ContentPart.Audio -> "[audio] ${describeMediaSource(part.source)}"
        is ContentPart.Video -> "[video] ${describeMediaSource(part.source)}"
    }

    fun List<ContentPart>.stripNonText(): List<ContentPart> =
        map { it as? ContentPart.Text ?: ContentPart.Text(mediaPlaceholder(it)) }

    return when (this) {
        is ChatMessage.User -> copy(parts = parts.stripNonText())
        is ChatMessage.ToolResult -> copy(parts = parts.stripNonText())
        else -> this
    }
}

