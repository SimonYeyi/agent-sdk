package io.github.yeyi.agent.mcp

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for managing multiple [McpServer] instances.
 */
public class McpServerRegistry {
    private val servers = ConcurrentHashMap<String, McpServer>()

    public fun register(server: McpServer): McpServerRegistry = apply {
        require(!servers.containsKey(server.name)) { "Server with name '${server.name}' is already registered" }
        servers[server.name] = server
    }

    internal suspend fun listTools(serverName: String): String {
        val server = servers[serverName] ?: return "Server not found: $serverName"
        return server.listTools().toString()
    }

    internal suspend fun callTool(serverName: String, params: JsonElement): String {
        val server = servers[serverName] ?: return "Server not found: $serverName"
        return server.callTool(params).toString()
    }

    public suspend fun closeAll() {
        servers.values.forEach { runCatching { it.close() } }
        servers.clear()
    }

    internal fun buildDescription(): String = servers.values.joinToString("\n") {
        "- ${it.name}: ${it.description}"
    }
}