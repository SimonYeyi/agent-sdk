package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.toolset.ToolsetRegistry
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * MCP 服务注册表。
 *
 * 持有已注册的 [Mcp] 服务（作为一种特殊的 [io.github.yeyi.agent.toolset.Toolset]
 * 复用 toolset 框架的发现与代理调用能力）。初始使用私有存储;
 * [mergeInto] 并入宿主的共享 toolset 注册表后,存储恒等于该共享本体 —— 此后
 * register / unregisterAll 直接作用于共享注册表,与宿主 Agent 的 toolset 视图
 * 保持一致（注册与移除均动态传播）。
 *
 * `register` 时把 [clientInfo] 注入到每个 [Mcp] 实例的 [McpClient]。
 * `unregisterAll` 时从共享注册表按名移除所有 Mcp 条目（普通 Toolset 不受影响）,
 * 然后关闭所有 MCP 连接 —— 移除同步完成,返回时不存在可激活的残留条目。
 *
 * @param clientInfo 注入到每个 MCP 客户端的 [ClientInfo]。
 */
public class McpRegistry(private val clientInfo: ClientInfo) {
    /** 当前生效存储 — 初始私有, [mergeInto] 后恒等于宿主的共享 toolset 注册表。 */
    private var toolsetRegistry = ToolsetRegistry()

    /**
     * 把存储内容并入 [registry] 并接管其为本存储。
     * [mcps][io.github.yeyi.agent.mcp.mcps] DSL 与 team builder 的统一并入入口。
     * 恒等守卫: 目标即当前存储时直接返回（重复 mergeInto 幂等）。
     */
    public fun mergeInto(registry: ToolsetRegistry) {
        if (toolsetRegistry === registry) return
        registry.register(toolsetRegistry.all())
        toolsetRegistry = registry
    }

    /** 注册单个 [Mcp] 服务,自动注入 [clientInfo];名称重复抛 [IllegalArgumentException]。 */
    public fun register(mcp: Mcp) {
        mcp.client.clientInfo = clientInfo
        toolsetRegistry.register(mcp)
    }

    /** 批量注册 [Mcp] 服务。 */
    public fun register(mcps: Iterable<Mcp>) {
        mcps.forEach(::register)
    }

    /** 按名称查找已注册的 [Mcp];存储共享后混有普通 Toolset,类型不符视为未注册。 */
    public fun get(name: String): Mcp =
        toolsetRegistry.getOrNull(name) as? Mcp
            ?: throw NoSuchElementException("Mcp with name '$name' not found")

    /** 返回已注册的所有 [Mcp] 服务（注册顺序快照;共享后过滤掉普通 Toolset）。 */
    public fun all(): List<Mcp> = toolsetRegistry.all().filterIsInstance<Mcp>()

    /**
     * 从存储(=共享本体)按名移除所有 Mcp 条目,普通 Toolset 不受影响;然后异步关闭
     * 所有连接。返回关闭协程的 [Job],调用方可 `join()` 等待。移除同步完成 ——
     * 返回后 `load_toolset` / 派发路径均查不到已注销的 Mcp,无重新激活可能。
     */
    @OptIn(DelicateCoroutinesApi::class)
    public fun unregisterAll(): Job {
        val snapshot = all()
        snapshot.forEach { toolsetRegistry.unregister(it.name) }
        return GlobalScope.launch {
            snapshot.forEach { runCatching { it.client.close() } }
        }
    }
}
