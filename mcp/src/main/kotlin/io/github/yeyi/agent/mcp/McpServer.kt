package io.github.yeyi.agent.mcp

import kotlinx.serialization.json.JsonElement

/**
 * MCP Server interface.
 *
 * Represents a remote MCP server that provides tools via the MCP protocol.
 * Users typically don't implement this directly — use [GenericMcpServer] with
 * a concrete [McpTransport] implementation instead.
 */
public interface McpServer {
    /** Unique identifier for this server. */
    public val name: String

    /** Human-readable description of this server's functionality. */
    public val description: String

    /** The transport used to communicate with this server. */
    public val transport: McpTransport

    /**
     * List all tools available on this server.
     * Corresponds to MCP protocol's `tools/list` endpoint.
     * Returns the raw JSON-RPC response result.
     */
    public suspend fun listTools(): JsonElement

    /**
     * Call a tool on this server.
     * Corresponds to MCP protocol's `tools/call` endpoint.
     * Accepts a raw JSON-RPC request and returns the raw JSON-RPC response result.
     */
    public suspend fun callTool(params: JsonElement): JsonElement

    /**
     * Release all resources held by this server (e.g., process, connection).
     * After calling close, this server should not be used.
     */
    public suspend fun close()
}