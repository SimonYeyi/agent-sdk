package io.github.yeyi.agent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 多媒体资源的统一来源抽象，三种模态 (image/audio/video) 共用同一组变体。
 *
 * - [Http]  : 公网 URL 或内网可路由 URL，由 LLM provider 主动 fetch。
 * - [Data]  : base64 内联；适用于 image 和短 audio；video 由 provider 实现层拒绝。
 * - [FileId]: provider 托管的文件 ID（OpenAI files API、Anthropic files API）。
 */
@Serializable
public sealed interface MediaSource {
    @Serializable
    @SerialName("url")
    public data class Http(public val url: String) : MediaSource

    @Serializable
    @SerialName("data")
    public data class Data(public val mimeType: String, public val base64: String) : MediaSource

    @Serializable
    @SerialName("fileId")
    public data class FileId(public val id: String) : MediaSource
}
