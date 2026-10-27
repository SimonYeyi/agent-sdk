package io.github.yeyi.agent.demo.team.voice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 火山 Seed-ASR WebSocket 二进制帧编解码。
 *
 * 帧结构：4 字节 Header + [可选 Sequence] + 4 字节 Payload Size + Payload。
 *
 * 迁移自 agentos 项目同文件（纯 JVM 算法，无 Android 依赖，未来可提取公共模块）。
 */
object VolcengineAsrFrameCodec {

    const val MSG_FULL_CLIENT = 0x1
    const val MSG_AUDIO_ONLY = 0x2
    const val MSG_FULL_SERVER = 0x9
    const val MSG_ERROR = 0xF

    private const val PROTOCOL_VERSION = 0x1
    private const val HEADER_SIZE = 0x1

    private const val FLAG_NONE = 0x0
    private const val FLAG_SEQUENCE = 0x1
    private const val FLAG_LAST_PACKET = 0x2

    private const val SERIAL_NONE = 0x0
    private const val SERIAL_JSON = 0x1

    private const val COMPRESS_NONE = 0x0
    private const val COMPRESS_GZIP = 0x1

    data class ParsedServerFrame(
        val messageType: Int,
        val sequence: Int?,
        val payload: ByteArray,
        val errorCode: Int? = null,
        val errorMessage: String? = null,
    )

    fun encodeFullClientRequest(jsonPayload: String): ByteArray {
        val compressed = gzipCompress(jsonPayload.toByteArray(Charsets.UTF_8))
        return buildFrame(
            messageType = MSG_FULL_CLIENT,
            flags = FLAG_NONE,
            serialization = SERIAL_JSON,
            compression = COMPRESS_GZIP,
            sequence = null,
            payload = compressed,
        )
    }

    fun encodeAudioOnly(audioData: ByteArray, isLastPacket: Boolean): ByteArray {
        val compressed = gzipCompress(audioData)
        return buildFrame(
            messageType = MSG_AUDIO_ONLY,
            flags = if (isLastPacket) FLAG_LAST_PACKET else FLAG_NONE,
            serialization = SERIAL_NONE,
            compression = COMPRESS_GZIP,
            sequence = null,
            payload = compressed,
        )
    }

    fun parseServerFrame(bytes: ByteArray): ParsedServerFrame {
        require(bytes.size >= 4) { "ASR frame too short: ${bytes.size}" }
        val header0 = bytes[0].toInt() and 0xFF
        val header1 = bytes[1].toInt() and 0xFF
        val header2 = bytes[2].toInt() and 0xFF

        val messageType = (header1 shr 4) and 0xF
        val flags = header1 and 0xF
        val compression = header2 and 0xF

        var offset = 4
        val sequence = when (flags) {
            FLAG_SEQUENCE, 0x3 -> {
                require(bytes.size >= offset + 4) { "ASR frame missing sequence" }
                val seq = readIntBE(bytes, offset)
                offset += 4
                seq
            }
            else -> null
        }

        if (messageType == MSG_ERROR) {
            require(bytes.size >= offset + 8) { "ASR error frame too short" }
            val errorCode = readIntBE(bytes, offset)
            offset += 4
            val errorSize = readIntBE(bytes, offset)
            offset += 4
            require(bytes.size >= offset + errorSize) { "ASR error payload truncated" }
            val errorMessage = bytes.copyOfRange(offset, offset + errorSize).toString(Charsets.UTF_8)
            return ParsedServerFrame(
                messageType = messageType,
                sequence = sequence,
                payload = ByteArray(0),
                errorCode = errorCode,
                errorMessage = errorMessage,
            )
        }

        require(bytes.size >= offset + 4) { "ASR frame missing payload size" }
        val payloadSize = readIntBE(bytes, offset)
        offset += 4
        require(bytes.size >= offset + payloadSize) { "ASR payload truncated" }
        val rawPayload = bytes.copyOfRange(offset, offset + payloadSize)
        val payload = if (compression == COMPRESS_GZIP) gzipDecompress(rawPayload) else rawPayload
        return ParsedServerFrame(
            messageType = messageType,
            sequence = sequence,
            payload = payload,
        )
    }

    private fun buildFrame(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
        sequence: Int?,
        payload: ByteArray,
    ): ByteArray {
        val hasSequence = sequence != null
        val capacity = 4 + (if (hasSequence) 4 else 0) + 4 + payload.size
        val buffer = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)
        buffer.put(((PROTOCOL_VERSION shl 4) or HEADER_SIZE).toByte())
        buffer.put(((messageType shl 4) or flags).toByte())
        buffer.put(((serialization shl 4) or compression).toByte())
        buffer.put(0x00)
        if (hasSequence) {
            buffer.putInt(sequence!!)
        }
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    private fun readIntBE(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size)
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    fun gzipDecompress(data: ByteArray): ByteArray {
        val bis = ByteArrayInputStream(data)
        return GZIPInputStream(bis).use { it.readBytes() }
    }
}
