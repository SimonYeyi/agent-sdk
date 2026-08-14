package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
public sealed class MessageContent {

    @Serializable
    public data class Text(val text: String) : MessageContent()

    @Serializable
    public data class Image(
        val parts: List<Resource>,
        val caption: String? = null
    ) : MessageContent()

    @Serializable
    public data class Audio(
        val resource: Resource,
        val durationSeconds: Int? = null,
        val transcription: String? = null
    ) : MessageContent()

    @Serializable
    public data class Video(
        val resource: Resource,
        val durationSeconds: Int? = null,
        val thumbnailUrl: String? = null
    ) : MessageContent()

    @Serializable
    public data class Document(
        val resource: Resource,
        val fileName: String,
        val mimeType: String? = null,
        val sizeBytes: Long? = null
    ) : MessageContent()

    @Serializable
    public data class Location(
        val latitude: Double,
        val longitude: Double,
        val name: String? = null
    ) : MessageContent()

    @Serializable
    public data class Contact(
        val name: String,
        val phone: String? = null,
        val email: String? = null
    ) : MessageContent()

    @Serializable
    public data class Reaction(
        val emoji: String,
        val targetMessageId: String
    ) : MessageContent()

    /**
     * 富文本混合消息。`parts` 按出现顺序排列;text/img/media 等元素各自转成对应的子类型。
     * 元素间相邻文本会被合并成单个 Text,行间用 '\n' 分隔。
     */
    @Serializable
    public data class Mixed(
        val parts: List<MessageContent>
    ) : MessageContent()

    @Serializable
    public data class Command(
        val command: String,
        val args: List<String> = emptyList()
    ) : MessageContent()

    @Serializable
    public data class SystemEvent(
        val eventType: String,
        val data: Map<String, String> = emptyMap()
    ) : MessageContent()

    @Serializable
    public data class Unknown(val rawContent: String) : MessageContent()

    /**
     * 媒体/文件资源。`Http` 表示已知的可路由 URL（如 Weixin 真 URL），
     * `Bytes` 表示已 fetch 的二进制内容（如 Feishu image_key 拉到的字节）。
     * 涵盖 image / audio / video / document，不限于"媒体"。
     */
    @Serializable
    public sealed class Resource {

        @Serializable
        public data class Http(public val url: String) : Resource()

        @Serializable
        public data class Bytes(public val data: ByteArray, public val mime: String) : Resource() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Bytes) return false
                return mime == other.mime && data.contentEquals(other.data)
            }

            override fun hashCode(): Int = 31 * mime.hashCode() + data.contentHashCode()

            override fun toString(): String {
                return "Data(mimeType=$mime, ${data.size / 1024}KB)"
            }
        }
    }

}
