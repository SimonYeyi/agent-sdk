package io.github.yeyi.agent.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
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
     * page.
     */
    public suspend fun listTools(cursor: String? = null): ListToolsResult

    /**
     * MCP protocol `tools/call` — invoke a tool.
     *
     * [params] is the raw JSON-RPC params object (must include `name`
     * and `arguments` per MCP spec). Returns the `content` field of the result.
     * If the server returns `isError: true`, throws [MCPServerException].
     */
    public suspend fun callTool(params: JsonElement): JsonElement

    /**
     * MCP protocol `ping` — liveness check.
     *
     * Returns `true` if the server responds with an empty result,
     * `false` if it responds with an error.
     */
    public suspend fun ping(): Boolean

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
 * The type parameter [T] is the params structure. Callers pass typed params
 * (e.g. [InitializeParams], [ListToolsParams]) and kotlinx.serialization
 * handles JSON encoding automatically.
 */
@Serializable
public data class JsonRpcRequest<T>(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: T? = null,
)

/**
 * `initialize` request params.
 */
@Serializable
public data class InitializeParams(
    val protocolVersion: String,
    val capabilities: JsonObject = JsonObject(emptyMap()),
    val clientInfo: ClientInfo,
)

/**
 * `tools/list` request params.
 */
@Serializable
public data class ListToolsParams(
    val cursor: String? = null,
)

/**
 * `tools/list` response result structure.
 */
@Serializable
public data class ListToolsResult(
    val tools: JsonArray,
    val nextCursor: String? = null,
)

/**
 * `ping` request params — typically empty.
 */
@Serializable
public object EmptyParams

/**
 * `tools/call` request params structure reference (not used for serialization;
 * arguments are passed through as raw [JsonElement] to avoid unnecessary
 * deserialization-reserialization).
 */
@Serializable
public data class CallToolParams(
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

/**
 * `tools/call` response result structure.
 */
@Serializable
public data class CallToolResult(
    val content: JsonElement? = null,
    val isError: Boolean = false,
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
 * JSON-RPC 2.0 notification message — server-to-client one-way message
 * with no `id` and no response expected.
 *
 * The [params] type parameter carries the notification payload; callers
 * decode to the typed structure via [kotlinx.serialization.json.decodeFromJsonElement].
 * For example, `notifications/cancelled` decodes `params` to [CancelledNotificationParams].
 */
@Serializable
public data class JsonRpcNotification<T>(
    val jsonrpc: String,
    val method: String,
    val params: T? = null,
)

/**
 * Notification params for `notifications/cancelled`.
 */
@Serializable
public data class CancelledNotificationParams(
    val requestId: Int,
)

/**
 * Empty notification params — used when the spec does not define any params.
 */
@Serializable
public object EmptyNotificationParams

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