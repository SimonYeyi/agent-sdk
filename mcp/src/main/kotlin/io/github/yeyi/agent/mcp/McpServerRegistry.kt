package io.github.yeyi.agent.mcp

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for managing multiple [McpServer] instances.
 */
public class McpServerRegistry {
    private val servers = ConcurrentHashMap<String, McpServer>()

    public fun register(server: McpServer): McpServerRegistry = apply {
        require(!servers.containsKey(server.name)) { "MCP Server with name '${server.name}' is already registered" }
        servers[server.name] = server
    }

    internal suspend fun listTools(serverName: String, cursor: String? = null): JsonElement {
        return getServer(serverName).listTools(cursor)
    }

    internal suspend fun listAllTools(serverName: String): JsonElement {
        return getServer(serverName).listAllTools()
    }

    internal suspend fun callTool(serverName: String, params: JsonElement): JsonElement {
        return getServer(serverName).callTool(params)
    }

    public suspend fun closeAll() {
        servers.values.forEach { runCatching { it.close() } }
        servers.clear()
    }

    internal fun buildDescription(): String = servers.values.joinToString("\n") {
        "- ${it.name}: ${it.description}"
    }

    private fun getServer(serverName: String): McpServer {
        return servers[serverName] ?: throw RuntimeException("MCP Server not found: $serverName")
    }
}