package io.github.yeyi.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Transport implementation using the MCP 2025-06-18 Streamable HTTP transport.
 *
 * The client posts JSON-RPC requests to a single MCP endpoint. The server MAY
 * respond with either `application/json` (single response) or
 * `text/event-stream` (SSE stream with possible progress notifications
 * interleaved). All requests carry the `MCP-Protocol-Version` header; if the
 * server returns an `Mcp-Session-Id` header, it is captured and echoed on
 * subsequent requests.
 *
 * Server-to-client notifications (e.g. `notifications/tools/list_changed`)
 * are not yet wired through a separate GET stream — that will arrive with
 * the long-lived notification channel work. The [notifications] flow is
 * currently empty for this transport.
 */
public class SseTransport(
    private val endpoint: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val protocolVersion: String = GenericMcpServer.SUPPORTED_PROTOCOL_VERSION,
) : McpTransport {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client: HttpClient = SharedHttpClient
    private var sessionId: String? = null

    override val notifications: Flow<JsonElement> = emptyFlow()

    override suspend fun send(request: JsonRpcRequest): JsonRpcResponse<JsonElement> {
        val body = json.encodeToString(request)

        return withContext(Dispatchers.IO) {
            val response: HttpResponse = client.post(endpoint) {
                contentType(ContentType.Application.Json)
                accept(ContentType.parse("application/json, text/event-stream"))
                header(MCP_PROTOCOL_VERSION_HEADER, protocolVersion)
                sessionId?.let { header(MCP_SESSION_ID_HEADER, it) }
                extraHeaders.forEach { (key, value) -> header(key, value) }
                setBody(body)
            }

            captureSessionId(response)

            if (!response.status.isSuccess()) {
                throw RuntimeException("HTTP error: ${response.status}")
            }

            val bodyText = response.bodyAsText()
            when (response.contentType()) {
                ContentType.Application.Json -> parseJsonResponse(bodyText, request.id)
                ContentType.Text.EventStream -> parseSseResponse(bodyText, request.id)
                else -> throw RuntimeException(
                    "Unexpected Content-Type: ${response.contentType()}"
                )
            }
        }
    }

    override suspend fun sendNotification(request: JsonRpcRequest) {
        val body = json.encodeToString(request)

        withContext(Dispatchers.IO) {
            val response: HttpResponse = client.post(endpoint) {
                contentType(ContentType.Application.Json)
                accept(ContentType.parse("application/json, text/event-stream"))
                header(MCP_PROTOCOL_VERSION_HEADER, protocolVersion)
                sessionId?.let { header(MCP_SESSION_ID_HEADER, it) }
                extraHeaders.forEach { (key, value) -> header(key, value) }
                setBody(body)
            }
            captureSessionId(response)
            // Notifications MUST receive 202 Accepted per spec; anything other
            // than 2xx is a failure.
            if (response.status != HttpStatusCode.Accepted && !response.status.isSuccess()) {
                throw RuntimeException("HTTP error on notification: ${response.status}")
            }
        }
    }

    override suspend fun close() {
        // HttpClient is shared; do not close it here.
    }

    private fun captureSessionId(response: HttpResponse) {
        response.headers[MCP_SESSION_ID_HEADER]?.let { sessionId = it }
    }

    private fun parseJsonResponse(body: String, expectedId: Int): JsonRpcResponse<JsonElement> {
        val response = json.decodeFromString<JsonRpcResponse<JsonElement>>(body)
        if (response.id != expectedId) {
            throw RuntimeException(
                "MCP response ID mismatch: expected $expectedId, got ${response.id}"
            )
        }
        return response
    }

    private fun parseSseResponse(body: String, expectedId: Int): JsonRpcResponse<JsonElement> {
        // Walk the SSE stream looking for the JSON-RPC response with the
        // expected id. Other events (progress notifications, etc.) are
        // skipped; a future iteration will route them into [notifications].
        for (event in SseEvent.parseAll(body)) {
            val data = event.data
            if (data.isEmpty() || data == "[DONE]") continue
            val element = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: continue
            val obj = element as? JsonObject ?: continue
            val id = (obj["id"] as? JsonPrimitive)?.intOrNull
            if (id == expectedId) {
                return json.decodeFromJsonElement(
                    JsonRpcResponse.serializer(JsonElement.serializer()),
                    element,
                )
            }
        }
        throw RuntimeException(
            "No matching response in SSE stream for id=$expectedId"
        )
    }

    internal companion object {
        internal const val MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
        internal const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"
    }
}

/**
 * Shared HttpClient for all [SseTransport] instances. Process-wide lifecycle
 * matches the JVM; tear-down happens on application shutdown.
 */
internal val SharedHttpClient: HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
    }
    expectSuccess = false
}

/**
 * Lightweight SSE event parser supporting the W3C Server-Sent Events
 * grammar: multi-line `data:` (joined by `\n`), `id:`, `event:`, `retry:`,
 * and `:` comment lines. Lines are split on `\n`; events are split on
 * blank lines (`\n\n`).
 */
internal data class SseEvent(
    val id: String? = null,
    val event: String? = null,
    val data: String = "",
    val retry: Long? = null,
) {
    companion object {
        fun parseAll(body: String): List<SseEvent> {
            val events = mutableListOf<SseEvent>()
            val current = StringBuilder()
            // Normalize line endings: split on \n, drop trailing \r on each.
            val lines = body.split('\n').map { it.trimEnd('\r') }
            for (line in lines) {
                if (line.isEmpty()) {
                    // Blank line: dispatch current event.
                    if (current.isNotEmpty()) {
                        events += parseEvent(current.toString())
                        current.clear()
                    }
                } else {
                    if (current.isNotEmpty()) current.append('\n')
                    current.append(line)
                }
            }
            if (current.isNotEmpty()) {
                events += parseEvent(current.toString())
            }
            return events
        }

        private fun parseEvent(raw: String): SseEvent {
            var id: String? = null
            var event: String? = null
            val data = StringBuilder()
            var retry: Long? = null
            for (line in raw.split('\n')) {
                if (line.isEmpty()) continue
                if (line.startsWith(':')) continue  // comment
                val colon = line.indexOf(':')
                val (field, value) = if (colon < 0) {
                    line to ""
                } else {
                    line.substring(0, colon) to
                        line.substring(colon + 1).trimStart(' ')
                }
                when (field) {
                    "id" -> id = value
                    "event" -> event = value
                    "data" -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(value)
                    }
                    "retry" -> retry = value.toLongOrNull()
                }
            }
            return SseEvent(
                id = id,
                event = event,
                data = data.toString(),
                retry = retry,
            )
        }
    }
}
