package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * [MediaArchive] 的文件系统默认实现 —— 作为 agent/core 的 caller 由 agent/session 模块提供。
 * 纯 IO(store/resolve), 不决定"什么值不值得落盘"——阈值由 [ArchivingMemory] 内部决定。
 *
 * 注入点:[SessionRepository.hydrateSession] 把 archive 实例传给 [JsonlBackedMemory],
 * 所有上层 Memory 通过 `Memory by` delegate 自动转发。
 *
 * caller app 如需自定义 archive (S3/DB/加密/TTL等), 直接实现 [MediaArchive] 接口
 * 并在 [JsonlBackedMemory] 构造时注入即可。
 *
 * @param rootDir 字节文件存储根目录。init 时若不存在则 `mkdirs` 创建。
 *                路径失效语义(agent 重启 / 跨进程):[resolve] 找不到 fileId 时
 *                抛 [IllegalStateException], 由 caller 决策恢复策略。
 */
public class FilesystemMediaArchive(
    private val rootDir: File,
) : MediaArchive {
    init {
        require(rootDir.exists() || rootDir.mkdirs()) {
            "Cannot create media root: $rootDir"
        }
    }

    override fun store(data: MediaSource.Data): MediaSource.Local {
        val fileId = UUID.randomUUID().toString()
        File(rootDir, fileId).writeBytes(Base64.getDecoder().decode(data.base64))
        return MediaSource.Local(fileId, data.mimeType)
    }

    override fun resolve(local: MediaSource.Local): MediaSource.Data {
        val file = File(rootDir, local.fileId)
        if (!file.exists()) throw IllegalStateException(
            "MediaArchive missing fileId=${local.fileId}",
        )
        return MediaSource.Data(
            mimeType = local.mimeType,
            base64 = Base64.getEncoder().encodeToString(file.readBytes()),
        )
    }
}
