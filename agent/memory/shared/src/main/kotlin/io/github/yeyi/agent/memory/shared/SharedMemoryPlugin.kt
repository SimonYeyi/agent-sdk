package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.AgentPlugin
import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.llm.LlmProvider
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * 占位 config 类型,满足 [AgentPlugin] 的 `C : Any` 约束。
 *
 * SharedMemoryPlugin 的所有配置在构造器完成,configure block 为 no-op。
 */
internal object SharedMemoryPluginConfig

/**
 * 共享记忆插件的接线类。
 *
 * 直接实现 [AgentPlugin],install 阶段完成:
 * 1. 经 loader.modify(RESERVED_USER) 确保 `user.md` 存在(不存在则写空正文模板)
 * 2. refreshUserProfile(userBody) 注入 `user.md` 正文到 persona 尾部独立段落(正文为空则跳过)
 * 3. registerTool(SharedMemoryLoadTool(loader))  → load_shared_memory
 * 4. registerTool(SharedMemorySaveTool(loader, maxFileCharSize, llmProvider, refreshUserProfile))
 *    → save_shared_memory,同时把刷新入口注入给工具
 *
 * **运行期刷新(user 永远最新)**:插件保留 [AgentPluginContext] 引用,把
 * `addInstruction(新正文, "user-profile")` 包装成回调交给 save 工具;工具写完 user 成功后调用它,
 * 把最新正文**原位替换**进 system prompt 段落。因此 user 永远等于"磁盘上那一刻的正文",
 * 不需要 turn hook,也不需要每轮重读:[io.github.yeyi.agent.ReActAgent] 每轮都重新
 * `persona.toString()`,运行期 `addInstruction` 立即生效。
 *
 * 插件不持有 [SharedMemoryStore]、也不调 reload:存储、快照与条数淘汰都由 [SharedMemoryLoader]
 * 独占维护,读写入口内部自动重建快照。
 */
internal class SharedMemoryPlugin(
    private val loader: SharedMemoryLoader,
    private val maxFileCharSize: Int,
    private val llmProvider: LlmProvider?,
) : AgentPlugin<SharedMemoryPluginConfig> {

    override val id: String = "shared-memory"

    override fun configure(block: SharedMemoryPluginConfig.() -> Unit) { /* no-op,配置已在构造器完成 */ }

    override fun install(context: AgentPluginContext) {
        // user 的刷新入口:save 写成功后调它,把最新正文原位替换进 persona。
        // label 固定 "user-profile":addInstruction 遇同名段落即原位替换,不追加。
        // 在这里捕获 context(而不是让工具去拿),契约最窄:工具只知道"user 变了,该通知一下"。
        val refreshUserProfile: (String) -> Unit = { newBody ->
            context.addInstruction(newBody, "user-profile")
        }

        val userBody = runBlocking { ensureUserMemory() }   // 内部 loader.modify 已自动 reload
        // 注入 user.md 正文到 persona 尾部独立段落(具名 label,便于后续原位刷新)。
        // 不在这里加标题/占位:"存在一个可写的 user 记忆"由 save_shared_memory 的 description 告知,
        // 每轮随 tools 下发,比 persona 里的固定文字更可靠。
        if (userBody.isNotBlank()) refreshUserProfile(userBody)

        // 注册两个工具(普通 Tool,不经过 CapabilityAdapter)
        context.registerTool(SharedMemoryLoadTool(loader))
        context.registerTool(
            SharedMemorySaveTool(loader, maxFileCharSize, llmProvider, refreshUserProfile)
        )
    }

    /**
     * 若 user.md 不存在,创建空模板;返回 user.md 正文(供 install 注入)。
     *
     * 已存在时 modify 会原样重写一次(原子替换),成本可接受;顺带的 reload 完成 install 首次条数检查。
     */
    private suspend fun ensureUserMemory(): String {
        val empty = SharedMemory.create(
            name = SharedMemory.RESERVED_USER,
            description = "用户画像,每轮主动注入 system prompt",
            now = Instant.now(),
        )
        val content = loader.modify(SharedMemory.RESERVED_USER) { current ->
            current ?: empty.render(body = "")
        } ?: return ""
        return SharedMemory.bodyOf(content)
    }
}
