package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * **线程安全**:多个并发 add() 调用通过 [Mutex] 序列化,单 archive 实例可被多 coroutine 共享
 * (与 [io.github.yeyi.agent.memory.InMemoryMemory] 的 contract 一致)。
 *
 * @param rootDir 字节文件存储根目录。init 时若不存在则 `mkdirs` 创建。
 *                路径失效语义(agent 重启 / 跨进程):[resolve] 找不到 fileId 时
 *                抛 [IllegalStateException], 由 caller 决策恢复策略。
 */
public class FilesystemMediaArchive(
    private val rootDir: File,
) : MediaArchive {
    private val mutex: Mutex = Mutex()

    init {
        require(rootDir.exists() || rootDir.mkdirs()) {
            "Cannot create media root: $rootDir"
        }
    }

    /**
     * 写入 base64 解码后的 bytes 到 `rootDir/{uuid}`。Bytes 写入异常 (IOException)
     * 正常传播,不包装 —— 与 [resolve] 行为一致。
     */
    override suspend fun store(data: MediaSource.Data): MediaSource.Local = mutex.withLock {
        val fileId = UUID.randomUUID().toString()
        File(rootDir, fileId).writeBytes(Base64.getDecoder().decode(data.base64))
        MediaSource.Local(fileId, data.mimeType)
    }

    override suspend fun resolve(local: MediaSource.Local): MediaSource.Data = mutex.withLock {
        val file = File(rootDir, local.fileId)
        if (!file.exists()) throw IllegalStateException(
            "MediaArchive missing fileId=${local.fileId}",
        )
        MediaSource.Data(
            mimeType = local.mimeType,
            base64 = Base64.getEncoder().encodeToString(file.readBytes()),
        )
    }
}
