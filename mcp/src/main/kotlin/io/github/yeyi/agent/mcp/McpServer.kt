package io.github.yeyi.agent.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * MCP Server — protocol contract for both remote MCP servers (accessed
 * via [McpTransport]) and in-process local server implementations.
 *
 * Every method on this interface corresponds to a JSON-RPC method the
 * server must speak, every type declared in this file corresponds to a
 * protocol structure. Implementation details (caching, transport encoding,
 * pagination loops) live in concrete implementations or SDK extensions.
 */
public interface McpServer {
    /** Unique identifier for this server. */
    public val name: String

    /** Human-readable description of this server's functionality. */
    public val description: String

    /** The transport used to communicate with this server. */
    public val transport: McpTransport

    /**
     * Establish the server's usable state.
     *
     * For remote servers this runs the MCP `initialize` handshake
     * (initialize request + `notifications/initialized` notification).
     * For in-process local servers this is where you'd set up resources.
     *
     * Idempotent — calling on an already-initialized server returns the
     * cached [InitializeResult] without re-handshaking. Implementations
     * should also auto-initialize lazily on the first `listTools` / `ping`
     * / `callTool` call, so callers can use either the explicit or
     * implicit form.
     */
    public suspend fun initialize(): InitializeResult

    /**
     * MCP protocol `tools/list` — list one page of tools.
     *
     * [cursor] is the pagination cursor from the previous page's
     * `nextCursor` field; pass `null` (the default) to fetch the first
     * page. Returns the raw JSON-RPC result, e.g.
     * `{"tools": [...], "nextCursor": "..."}`.
     */
    public suspend fun listTools(cursor: String? = null): JsonElement

    /**
     * MCP protocol `ping` — liveness check.
     *
     * Returns `true` if the server responds with an empty result,
     * `false` if it responds with an error.
     */
    public suspend fun ping(): Boolean

    /**
     * MCP protocol `tools/call` — invoke a tool.
     *
     * [params] is the raw JSON-RPC params object (must include `name`
     * and `arguments` per MCP spec). Returns the raw JSON-RPC result.
     */
    public suspend fun callTool(params: JsonElement): JsonElement

    /**
     * Release all resources held by this server (process, connection,
     * in-process state). After calling close, this server should not
     * be used.
     */
    public suspend fun close()
}

/**
 * MCP protocol method names — single source of truth so implementations
 * and transports don't hardcode JSON-RPC method strings.
 */
public object McpMethods {
    public const val INITIALIZE: String = "initialize"
    public const val PING: String = "ping"
    public const val TOOLS_LIST: String = "tools/list"
    public const val TOOLS_CALL: String = "tools/call"

    public const val NOTIFICATIONS_INITIALIZED: String = "notifications/initialized"
    public const val NOTIFICATIONS_CANCELLED: String = "notifications/cancelled"
    public const val NOTIFICATIONS_TOOLS_LIST_CHANGED: String = "notifications/tools/list_changed"
}

/**
 * MCP protocol implementation info — the `{name, version}` structure used
 * by both `clientInfo` and `serverInfo` fields in the `initialize`
 * handshake. Use [ClientInfo] when announcing ourselves to a server, and
 * [ServerInfo] when parsing a server's self-introduction.
 */
public sealed class Implementation {
    public abstract val name: String
    public abstract val version: String
}

@Serializable
public data class ClientInfo(
    override val name: String,
    override val version: String,
) : Implementation()

@Serializable
public data class ServerInfo(
    override val name: String,
    override val version: String,
) : Implementation()

/**
 * JSON-RPC 2.0 request message.
 *
 * For notifications (no response expected) the [id] field carries a value
 * the server is expected to ignore; transport implementations use the
 * [method] name (typically a `notifications/...` method) to decide whether
 * to expect a response.
 */
@Serializable
public data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonElement? = null,
)

/**
 * JSON-RPC 2.0 response message.
 *
 * The [result] type parameter is the typed `result` payload: the transport
 * layer carries it as [JsonElement] (the protocol doesn't fix a shape
 * across all methods), and callers decode to the typed structure they need
 * via [kotlinx.serialization.json.decodeFromJsonElement]. For example,
 * the `initialize` handshake decodes `result` to [InitializeResult].
 */
@Serializable
public data class JsonRpcResponse<T>(
    val jsonrpc: String,
    val id: Int,
    val result: T? = null,
    val error: JsonElement? = null,
)

/**
 * MCP protocol `initialize` response — the `result` field the server returns
 * to a client's initialize request.
 */
@Serializable
public data class InitializeResult(
    val protocolVersion: String,
    val serverInfo: ServerInfo,
    val capabilities: JsonObject = JsonObject(emptyMap()),
    val instructions: String? = null,
)