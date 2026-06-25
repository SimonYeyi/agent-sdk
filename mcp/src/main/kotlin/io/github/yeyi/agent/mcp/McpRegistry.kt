package io.github.yeyi.agent.mcp

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 注册表 —— 管理多个 [Mcp] 实例的注册与查找。
 *
 * 注册表负责：
 * - 按名称注册和管理多个 MCP 服务
 * - 提供按名称查找并调用 MCP 服务的工具列表与工具调用
 * - 统一注入客户端信息（[clientInfo]）
 * - 生成 MCP 服务的统一描述文本，供模型识别
 *
 * 与 [io.github.yeyi.agent.skill.SkillRegistry]、子 agent 注册表模式对齐，
 * 都是"能力注册 → 能力项集合 → 供模型发现与调用"的模式。
 */
public class McpRegistry(private val clientInfo: ClientInfo) {
    private val mcpMap = ConcurrentHashMap<String, Mcp>()

    /**
     * 注册一个 MCP 服务。
     *
     * @throws IllegalArgumentException 同名 MCP 已注册时抛出
     */
    public fun register(mcp: Mcp): McpRegistry = apply {
        require(!mcpMap.containsKey(mcp.name)) { "MCP with name '${mcp.name}' is already registered" }
        mcp.client.clientInfo = this.clientInfo
        mcpMap[mcp.name] = mcp
    }

    /** 批量注册多个 MCP 服务。 */
    public fun register(servers: Iterable<Mcp>) {
        servers.forEach(::register)
    }

    /**
     * 获取指定 MCP 服务的工具列表，返回 JSON 字符串。
     *
     * @throws NoSuchElementException 指定名称的 MCP 不存在时抛出
     */
    internal suspend fun toolsList(mcpName: String): String {
        return getMcp(mcpName).toolsList()
    }

    /**
     * 调用指定 MCP 服务上的工具，返回结果 JSON 字符串。
     *
     * @throws NoSuchElementException 指定名称的 MCP 不存在时抛出
     * @throws McpException 工具执行失败时抛出
     */
    internal suspend fun toolsCall(mcpName: String, params: JsonElement): String {
        return getMcp(mcpName).toolsCall(params)
    }

    /** 注销并关闭所有已注册的 MCP 服务，释放资源。 */
    public suspend fun unregisterAll() {
        mcpMap.values.forEach { runCatching { it.client.close() } }
        mcpMap.clear()
    }

    /** 构建所有已注册 MCP 服务的描述文本，格式为每行一个 "- 名称: 描述"。 */
    internal fun buildDescription(): String = mcpMap.values.joinToString("\n") {
        "- ${it.name}: ${it.description}"
    }

    private fun getMcp(mcpName: String): Mcp {
        return mcpMap[mcpName] ?: throw NoSuchElementException("MCP not found: $mcpName")
    }
}