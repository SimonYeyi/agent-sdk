package io.github.yeyi.agent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * LLM 聊天请求数据。
 *
 * @param messages 对话历史列表，首位通常是 [ChatMessage.System]
 * @param tools 可用工具定义列表，为空时不启用工具调用
 * @param temperature 采样温度，控制随机性；null 表示使用 provider 默认值
 * @param maxTokens 最大生成 token 数；null 表示不限制
 * @param stopSequences 遇到此列表中的字符串时停止生成
 */
public data class ChatRequest(
    public val messages: List<ChatMessage>,
    public val tools: List<ToolDefinition> = emptyList(),
    public val temperature: Double? = null,
    public val maxTokens: Int? = null,
    public val stopSequences: List<String> = emptyList()
) {
    override fun toString(): String = "ChatRequest(message=${messages.lastOrNull() ?: "empty"})"
}

@Serializable
public sealed interface ChatMessage {
    public val role: Role

    /** 系统消息，通常放入 [io.github.yeyi.agent.Persona] 渲染后的角色文本。 */
    @Serializable
    public data class System(public val content: String) : ChatMessage {
        override val role: Role = Role.System
    }

    /**
     * 用户消息,承载单条/多条内容块 (文本 + image/audio/video)。
     * 空 parts 等价于无消息,构造时拒。
     */
    @Serializable
    public data class User(public val parts: List<ContentPart>) : ChatMessage {
        init {
            require(parts.isNotEmpty()) { "ChatMessage.User.parts must not be empty" }
        }

        override val role: Role = Role.User
    }

    /**
     * LLM 回复消息。
     *
     * @param content 文字回复（可能为 null，此时 [toolCalls] 非空）
     * @param toolCalls LLM 决定调用的工具列表（可能为空）
     */
    @Serializable
    public data class Assistant(
        public val content: String? = null,
        public val toolCalls: List<ToolCall> = emptyList()
    ) : ChatMessage {
        override val role: Role = Role.Assistant
    }

    /**
     * 工具执行结果。与 [User] 对称,承载单条/多条内容块 (文本 + image/audio/video)。
     *
     * @param toolCallId 对应 [ToolCall.id]
     * @param toolName 工具名称
     * @param parts 执行结果内容块 (文本 + image/audio/video)
     * @param isError 是否为错误结果
     */
    @Serializable
    public data class ToolResult(
        public val toolCallId: String,
        public val toolName: String,
        public val parts: List<ContentPart>,
        public val isError: Boolean = false
    ) : ChatMessage {
        override val role: Role = Role.Tool
    }
}

/**
 * 消息角色枚举。
 *
 * @property System 系统消息（通常放 persona 文本）
 * @property User 用户消息
 * @property Assistant LLM 回复消息
 * @property Tool 工具执行结果（tool result 反馈给 LLM）
 */
public enum class Role { System, User, Assistant, Tool }

/** LLM 生成的工具调用请求。 */
@Serializable
public data class ToolCall(
    public val id: String,
    public val name: String,
    public val arguments: JsonElement
)

/**
 * 单条内容块，[ChatMessage.User] 与 [ChatMessage.ToolResult] 共用。
 * 4 个变体独立 sealed 而非合并为 Media(kind, source), 三种媒体未来会
 * 各自演化出差异化约束 (image 的 detail、audio 的 format、video 的 clip window)。
 */
@Serializable
public sealed interface ContentPart {
    public val kind: Kind
        get() = when (this) {
            is Text -> Kind.Text
            is Image -> Kind.Image
            is Audio -> Kind.Audio
            is Video -> Kind.Video
        }

    public enum class Kind { Text, Image, Audio, Video }

    @Serializable
    @SerialName("text")
    public data class Text(public val text: String) : ContentPart

    @Serializable
    @SerialName("image")
    public data class Image(public val source: MediaSource) : ContentPart

    @Serializable
    @SerialName("audio")
    public data class Audio(public val source: MediaSource) : ContentPart

    @Serializable
    @SerialName("video")
    public data class Video(public val source: MediaSource) : ContentPart
}

/**
 * 多媒体资源的统一来源抽象，四种模态 (image/audio/video) 共用同一组变体。
 *
 * - [Http]  : 公网 URL 或内网可路由 URL，由 LLM provider 主动 fetch。
 * - [Data]  : base64 内联；适用于 image 和短 audio；video 由 provider 实现层拒绝。
 * - [FileId]: provider 托管的文件 ID（OpenAI files API、Anthropic files API）。
 * - [Local] : agent 持有的本地文件引用(UUID);在请求边界由适配层解析为字节,
 *             跨 query 复用同一图时用此避免重复 inline base64。
 *             Provider 实现层**不支持** [Local]。
 */
@Serializable
public sealed interface MediaSource {
    @Serializable
    @SerialName("url")
    public data class Http(public val url: String) : MediaSource {
        override fun toString(): String {
            return "Http(url=${if (url.length > 64) url.take(63) + "…" else url})"
        }
    }

    @Serializable
    @SerialName("data")
    public data class Data(public val mimeType: String, public val base64: String) : MediaSource {
        override fun toString(): String {
            return "Data(mimeType=$mimeType, ${base64.length / 1024}KB)"
        }
    }

    @Serializable
    @SerialName("fileId")
    public data class FileId(public val id: String) : MediaSource

    @Serializable
    @SerialName("local")
    public data class Local(
        public val fileId: String,
        public val mimeType: String,
    ) : MediaSource
}

/**
 * 工具定义（用于告诉 LLM 有哪些工具可用）。
 *
 * 此类是 SDK → LLM 的输出数据结构，不参与实际执行。
 *
 * @param name 工具名称，对应 [io.github.yeyi.agent.tool.Tool.name]
 * @param description 工具描述，供 LLM 理解用途
 * @param parametersSchema 参数 JSON Schema，LLM 据此生成调用参数
 */
public data class ToolDefinition(
    public val name: String,
    public val description: String,
    public val parametersSchema: JsonObject
) {
    override fun toString(): String {
        val escapedDesc = description
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """{"name": "$name", "description": "$escapedDesc", "parameters_schema": $parametersSchema}"""
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
        is MediaSource.Local -> "local fileId=${source.fileId.take(8)}"
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

/** 集合中的文本部分，多段以换行拼接；媒体块不参与。 */
public val Collection<ContentPart>.text: String
    get() = filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
