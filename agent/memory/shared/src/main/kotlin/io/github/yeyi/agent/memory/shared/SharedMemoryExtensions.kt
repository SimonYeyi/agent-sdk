package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.llm.LlmProvider
import java.nio.file.Path

/**
 * 注册共享记忆插件到 Agent —— **本模块唯一的公开 API**。
 *
 * 与 core 的 `AgentBuilder.memory(Memory, maxRounds)`(短期会话历史)区分开,所以不叫 `memory(...)`:
 * `sharedMemory` 是跨会话的 markdown 记忆,两者正交。
 *
 * @param directory 记忆文件根目录,宿主必传(不存在会自动创建)。
 *   同一目录在进程内共享同一份快照、同一套锁与同一个条数上限,多个 Agent 传同一目录即共享同一份记忆。
 * @param maxFileCount 目录里最多保留的记忆条数,超出则按最久未访问淘汰。默认 20
 * @param maxFileCharSize 单条记忆的字符数上限(≈ 上下文/token 预算),超则同步压缩。默认 8000
 *   注意这是**软上限**:压缩失败时超长正文照存;超过 `maxFileCharSize × 4` 的入参才直接拒绝。
 * @param llmProvider 压缩用 LLM;null 时回退到 AgentContext.llmProvider。默认 null
 */
public fun AgentBuilder.sharedMemory(
    directory: Path,
    maxFileCount: Int = 20,
    maxFileCharSize: Int = 8000,
    llmProvider: LlmProvider? = null,
) {
    // 校验必须在 of(...) 之前:of 会 Files.createDirectories,参数非法时不应该先建目录
    require(maxFileCount > 0) { "maxFileCount must be positive: $maxFileCount" }
    require(maxFileCharSize > 0) { "maxFileCharSize must be positive: $maxFileCharSize" }
    plugin(
        SharedMemoryPlugin(
            loader = SharedMemoryLoader.of(directory, maxFileCount),
            maxFileCharSize = maxFileCharSize,
            llmProvider = llmProvider,
        )
    )
}
