package io.github.yeyi.agent.mcp

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for managing multiple [McpServer] instances.
 */
public class McpServerRegistry(private val clientInfo: ClientInfo) {
    private val servers = ConcurrentHashMap<String, McpServer>()

    public fun register(server: McpServer): McpServerRegistry = apply {
        require(!servers.containsKey(server.name)) { "MCP Server with name '${server.name}' is already registered" }
        (server as? GenericMcpServer)?.clientInfo = this.clientInfo
        servers[server.name] = server
    }

    public fun register(servers: Iterable<McpServer>) {
        servers.forEach(::register)
    }

    internal suspend fun listAllTools(serverName: String): ListToolsResult {
        return getServer(serverName).listAllTools()
    }

    internal suspend fun callTool(serverName: String, params: JsonElement): JsonElement {
        return getServer(serverName).callTool(params)
    }

    public suspend fun unregisterAll() {
        servers.values.forEach { runCatching { it.close() } }
        servers.clear()
    }

    internal fun buildDescription(): String = servers.values.joinToString("\n") {
        "- ${it.name}: ${it.description}"
    }

    private fun getServer(serverName: String): McpServer {
        return servers[serverName] ?: throw IllegalArgumentException("MCP Server not found: $serverName")
    }
}