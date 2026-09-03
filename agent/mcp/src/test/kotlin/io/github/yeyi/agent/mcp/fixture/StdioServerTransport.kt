package io.github.yeyi.agent.mcp.fixture

import io.github.yeyi.agent.mcp.InitializeResult
import io.github.yeyi.agent.mcp.JsonRpcNotification
import io.github.yeyi.agent.mcp.JsonRpcRequest
import io.github.yeyi.agent.mcp.JsonRpcResponse
import io.github.yeyi.agent.mcp.McpMethods
import io.github.yeyi.agent.mcp.McpServer
import io.github.yeyi.agent.mcp.McpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.serializer
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Server-side counterpart of [io.github.yeyi.agent.mcp.StdioTransport].
 *
 * Implements [McpTransport] from the server's perspective with the same
 * method names but inverted routing roles, mirroring the client side:
 * - [send] is the incoming request entry point: each [JsonRpcRequest] read
 *   off the wire (by [run]) is dispatched to [server], and the resulting
 *   [JsonRpcResponse] is returned for [run] to write to stdout.
 * - [sendNotification] is the incoming notification entry point: each
 *   client→server [JsonRpcNotification] read off the wire (by [run]) is
 *   forwarded to [server] for handling. The fixture currently leaves this
 *   as a no-op since the test surfaces do not exercise incoming
 *   client→server notifications yet.
 * - [notifications] is the outgoing notification production channel:
 *   server→client notifications are pushed here by [McpServer] (which
 *   casts the declared `Flow` to its backing [MutableSharedFlow] to call
 *   `tryEmit`), and [run] drains the flow to stdout so the client can
 *   subscribe.
 *
 * The wire-side [run] loop is the only orchestration left outside the
 * [McpTransport] interface: it pulls lines off stdin and routes each to
 * [send] (request) or [sendNotification] (incoming notification), while a
 * sibling collector concurrently drains [notifications] to stdout.
 *
 * @param server The [McpServer] to dispatch incoming requests to.
 */
public class StdioServerTransport(
    private val server: McpServer,
    private val onClientNotification: (JsonRpcNotification<JsonElement>) -> Unit = {},
) : McpTransport {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val stdin: BufferedReader =
        BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    private val stdout: BufferedWriter =
        BufferedWriter(OutputStreamWriter(System.out, Charsets.UTF_8))
    private val writeMutex = Mutex()


    /**
     * Outgoing notification production channel (server→client). Backed by
     * a [MutableSharedFlow] that buffers server→client notifications
     * emitted by [McpServer]; the client subscribes to this view to
     * receive them. To push, [McpServer] casts to the backing
     * [MutableSharedFlow] and calls `tryEmit` — [run] drains the flow to
     * stdout concurrently.
     */
    override val notifications: Flow<JsonRpcNotification<JsonElement>> =
        MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 64,
        )

    /**
     * Handle an incoming client request: dispatch to [server], build the
     * [JsonRpcResponse], and return it. Pure function — [run] writes the
     * response to stdout.
     */
    override suspend fun send(
        request: JsonRpcRequest<JsonElement>,
    ): JsonRpcResponse<JsonElement> {
        val result = when (request.method) {
            McpMethods.INITIALIZE -> {
                json.encodeToJsonElement(serializer<InitializeResult>(), server.initialize())
            }

            else -> error("unhandled method in fixture: ${request.method}")
        }
        return JsonRpcResponse(jsonrpc = "2.0", id = request.id, result = result)
    }

    /**
     * Incoming notification entry point: invoked by [run] when a line read
     * off stdin parses as a [JsonRpcNotification] (no `id`). Forwards the
     * notification to [server] for handling via the [onClientNotification]
     * callback supplied at construction. The fixture default is a no-op
     * since the basic protocol mock ([FixtureMcpServer]) does not need to
     * react to client→server notifications; test surfaces can supply a
     * callback to drive an outgoing reaction.
     */
    override suspend fun sendNotification(notification: JsonRpcNotification<JsonElement>) {
        onClientNotification(notification)
    }

    override suspend fun close() {
        runCatching { stdin.close() }
        runCatching { stdout.close() }
    }

    /**
     * Run the server-side stdio loop with two concurrent activities:
     * 1. Drain the [notifications] flow to stdout (the server→client
     *    wire).
     * 2. Read JSON-RPC lines from stdin. Each request is routed to [send]
     *    (which dispatches to [server] and returns a response we then
     *    write to stdout); each client→server notification is routed to
     *    [sendNotification] for [server] to handle.
     *
     * Blocks until stdin closes (parent process closed the pipe, typically
     * because [io.github.yeyi.agent.mcp.StdioTransport.close] was called
     * which closes our stdin). [coroutineScope] cancels the drain
     * collector when the stdin reader exits.
     */
    public suspend fun run() {
        coroutineScope {
            launch(Dispatchers.Default) {
                notifications.collect { writeLine(json.encodeToString(it)) }
            }
            withContext(Dispatchers.IO) {
                for (line in stdin.lineSequence()) {
                    val parsed = json.parseToJsonElement(line) as? JsonObject
                        ?: error("expected JSON object, got: $line")
                    if (parsed["id"] == null) {
                        val notification = json.decodeFromJsonElement(
                            serializer<JsonRpcNotification<JsonElement>>(),
                            parsed,
                        )
                        sendNotification(notification)
                    } else {
                        val request: JsonRpcRequest<JsonElement> = json.decodeFromJsonElement(
                            serializer<JsonRpcRequest<JsonElement>>(),
                            parsed,
                        )
                        val response = send(request)
                        writeLine(json.encodeToString(response))
                    }
                }
            }
        }
    }

    private suspend fun writeLine(line: String) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                stdout.write(line)
                stdout.newLine()
                stdout.flush()
            }
        }
    }
}