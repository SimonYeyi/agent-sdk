package io.github.yeyi.agent.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * MCP 服务协议契约 —— 定义 MCP 协议的方法集与数据结构。
 *
 * 这是一个纯协议层接口，既可以由 [McpClient]（客户端代理，通过传输层调用远端服务）
 * 实现，也可以由进程内本地服务直接实现。接口中的每个方法对应 MCP 协议的一个
 * JSON-RPC 方法，本文件中声明的每个类型对应一个协议结构体。
 *
 * 实现细节（缓存、传输编解码、分页遍历）由具体实现类或 SDK 扩展函数负责，
 * 本接口只定义契约。
 *
 * 面向 Agent 的注册概念（name/description）见 [Mcp] 接口。
 */
public interface McpServer {
    public companion object {
        internal const val SUPPORTED_PROTOCOL_VERSION = "2025-06-18"
    }

    /** 用于与该 MCP 服务通信的传输层实例。 */
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
     * MCP 协议 `tools/call` —— 调用指定工具。
     *
     * [params] 为 JSON-RPC 原始参数对象，按 MCP 协议规范必须包含
     * `name`（工具名）和 `arguments`（工具入参）。返回结果的 `content` 字段。
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
    public const val NOTIFICATIONS_MESSAGE: String = "notifications/message"
    public const val NOTIFICATIONS_PROGRESS: String = "notifications/progress"
    public const val NOTIFICATIONS_RESOURCES_LIST_CHANGED: String = "notifications/resources/list_changed"
    public const val NOTIFICATIONS_RESOURCES_UPDATED: String = "notifications/resources/updated"
    public const val NOTIFICATIONS_PROMPTS_LIST_CHANGED: String = "notifications/prompts/list_changed"
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

/** 客户端标识信息，在 `initialize` 握手时发送给 MCP 服务端。 */
@Serializable
public data class ClientInfo(
    override val name: String,
    override val version: String,
) : Implementation()

/**
 * Client capabilities sent to the server during initialize.
 * Null fields indicate the client does not support that feature.
 */
@Serializable
public data class ClientCapabilities(
    val roots: RootsObject? = null,
    // SamplingObject (singleton) = supported, null = not supported
    val sampling: SamplingObject? = null,
    val elicitation: ElicitationObject? = null,
    val experimental: JsonObject? = null,
)

@Serializable
public data class RootsObject(
    val listChanged: Boolean? = null,
)

@Serializable
public object SamplingObject

@Serializable
public object ElicitationObject

/** 服务端标识信息，`initialize` 握手响应中返回。 */
@Serializable
public data class ServerInfo(
    override val name: String,
    override val version: String,
) : Implementation()

/**
 * Server capabilities received from the server during initialize.
 * Null fields indicate the server does not support that feature.
 */
@Serializable
public data class ServerCapabilities(
    val logging: LoggingObject? = null,
    val completions: CompletionsObject? = null,
    val tools: ToolsObject? = null,
    val resources: ResourcesObject? = null,
    val prompts: PromptsObject? = null,
    val sampling: SamplingObject? = null,
    val experimental: JsonObject? = null,
)

@Serializable
public object LoggingObject

@Serializable
public object CompletionsObject

@Serializable
public data class ToolsObject(
    val listChanged: Boolean? = null,
)

@Serializable
public data class ResourcesObject(
    val subscribe: Boolean? = null,
    val listChanged: Boolean? = null,
)

@Serializable
public data class PromptsObject(
    val listChanged: Boolean? = null,
)

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
    val capabilities: ClientCapabilities,
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
    val reason: String? = null,
)

/**
 * Log level for `notifications/message`.
 */
@Serializable
public enum class LogLevel {
    @SerialName("debug") DEBUG,
    @SerialName("info") INFO,
    @SerialName("warning") WARNING,
    @SerialName("error") ERROR,
}

/**
 * Notification params for `notifications/message`.
 */
@Serializable
public data class MessageNotificationParams(
    val level: LogLevel,
    val logger: String? = null,
    val data: JsonElement? = null,
)

/**
 * Notification params for `notifications/progress`.
 */
@Serializable
public data class ProgressNotificationParams(
    val progressToken: String,
    val progress: Double,
    val total: Double? = null,
)

/**
 * Notification params for `notifications/resources/updated`.
 */
@Serializable
public data class ResourcesUpdatedNotificationParams(
    val uri: String,
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
    val capabilities: ServerCapabilities,
    val instructions: String? = null,
)