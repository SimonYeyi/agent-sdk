package io.github.yeyi.agent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 用户回合（user turn）中的单条内容块。
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
