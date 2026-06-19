package io.github.yeyi.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Transport implementation using the MCP 2025-06-18 Streamable HTTP transport.
 *
 * Architecture:
 * - A persistent GET SSE connection receives server-to-client notifications
 *   and progress updates.
 * - Short-lived POST requests carry JSON-RPC calls and receive immediate
 *   responses (application/json).
 * - Session affinity is maintained via the Mcp-Session-Id header.
 *
 * Usage:
 *   val transport = SseTransport(endpoint)
 *   transport.initialize()  // Must be called before send()
 */
public class SseTransport(
    private val endpoint: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val protocolVersion: String = McpServer.SUPPORTED_PROTOCOL_VERSION,
    private val enableNotifications: Boolean = false,
) : McpTransport {
    // Internal defaults for SSE notification channel
    private object Defaults {
        const val SSE_RECONNECT_DELAY_MS = 5000L
        const val SESSION_TIMEOUT_MS = 4000L
        const val MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
        const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Dedicated clients with appropriate timeouts
    private val postClient: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 20_000
        }
        expectSuccess = false
    }

    private val sseClient: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 20_000
        }
        expectSuccess = false
    }

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var initialized: Boolean = false

    private var sseJob: Job? = null

    // Track pending cancellation jobs for graceful shutdown
    private val cancellationJobs = mutableListOf<Job>()

    // Scope for background tasks (SSE listener, cancellation notifications)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _notifications = MutableSharedFlow<JsonRpcNotification<JsonElement>>(
        replay = 0,
        extraBufferCapacity = 256,
    )

    override val notifications: Flow<JsonRpcNotification<JsonElement>> = _notifications.asSharedFlow()

    /**
     * Initializes the transport by establishing the SSE connection and obtaining
     * a session ID from the server.
     *
     * This only establishes the transport layer (SSE connection + session).
     * The actual MCP protocol handshake (initialize request) is handled by
     * [GenericMcpServer.initialize].
     *
     * Must be called before [send] or [sendNotification].
     */
    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        if (initialized) return@withContext

        if (enableNotifications) {
            // Establish SSE connection to receive notifications
            startSseConnection()

            // Wait for session ID from SSE connection
            val retryDelayMs = 50L
            val maxAttempts = (Defaults.SESSION_TIMEOUT_MS / retryDelayMs).toInt()
            repeat(maxAttempts) {
                if (sessionId != null) return@withContext
                delay(retryDelayMs)
            }
        }

        // Fallback: try a POST ping request to get sessionId if not already obtained
        if (sessionId == null) {
            runCatching {
                val pingRequest = JsonRpcRequest(
                    id = 0,
                    method = McpMethods.PING,
                    params = EmptyParams,
                )
                val fallbackResponse = postClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    header(Defaults.MCP_PROTOCOL_VERSION_HEADER, protocolVersion)
                    extraHeaders.forEach { (k, v) -> header(k, v) }
                    setBody(json.encodeToString(pingRequest))
                }
                captureSessionId(fallbackResponse)
            }
        }

        // sessionId is optional - many servers work without it
        initialized = true
    }

    override suspend fun send(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> {
        ensureInitialized()

        val body = json.encodeToString(request)

        return try {
            val response: HttpResponse = postClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                header(Defaults.MCP_PROTOCOL_VERSION_HEADER, protocolVersion)
                sessionId?.let { header(Defaults.MCP_SESSION_ID_HEADER, it) }
                extraHeaders.forEach { (key, value) -> header(key, value) }
                setBody(body)
            }

            captureSessionId(response)

            if (!response.status.isSuccess()) {
                throw RuntimeException("HTTP error: ${response.status}")
            }

            val bodyText = response.bodyAsText()
            parseJsonResponse(bodyText, request.id)
        } catch (e: CancellationException) {
            notifyCancelledAsync(request.id)
            throw e
        }
    }

    override suspend fun sendNotification(request: JsonRpcRequest<JsonElement>) {
        ensureInitialized()

        val body = json.encodeToString(request)

        val response: HttpResponse = postClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(Defaults.MCP_PROTOCOL_VERSION_HEADER, protocolVersion)
            sessionId?.let { header(Defaults.MCP_SESSION_ID_HEADER, it) }
            extraHeaders.forEach { (key, value) -> header(key, value) }
            setBody(body)
        }
        captureSessionId(response)

        // Per MCP spec, notifications SHOULD receive 202 Accepted
        if (response.status != HttpStatusCode.Accepted && !response.status.isSuccess()) {
            throw RuntimeException("HTTP error on notification: ${response.status}")
        }
    }

    override suspend fun close() {
        sseJob?.cancel()
        sseJob = null

        // Cancel all pending cancellation notification jobs
        cancellationJobs.forEach { it.cancel() }
        cancellationJobs.clear()

        backgroundScope.cancel()
        postClient.close()
        sseClient.close()
        initialized = false
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private fun ensureInitialized() {
        check(initialized) {
            "SseTransport is not initialized. Call initialize() before sending messages."
        }
    }

    /**
     * Establishes a persistent SSE connection and starts reading events.
     * Automatically reconnects on failure with a configurable delay.
     */
    private fun startSseConnection() {
        sseJob = backgroundScope.launch {
            while (isActive) {
                try {
                    val response: HttpResponse = sseClient.get(endpoint) {
                        accept(ContentType.Text.EventStream)
                        header(Defaults.MCP_PROTOCOL_VERSION_HEADER, protocolVersion)
                        sessionId?.let { header(Defaults.MCP_SESSION_ID_HEADER, it) }
                        extraHeaders.forEach { (key, value) -> header(key, value) }
                    }

                    captureSessionId(response)

                    if (!response.status.isSuccess()) {
                        throw RuntimeException("SSE connection failed: ${response.status}")
                    }

                    val channel = response.bodyAsChannel()
                    readSseEvents(channel)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Log and reconnect after delay
                    delay(Defaults.SSE_RECONNECT_DELAY_MS)
                }
            }
        }
    }

    /**
     * Reads SSE events from the given channel, parses them, and emits
     * notifications to [_notifications].
     */
    private suspend fun readSseEvents(channel: io.ktor.utils.io.ByteReadChannel) {
        val currentEvent = StringBuilder()

        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break

            if (line.isEmpty()) {
                // Blank line: dispatch current event
                if (currentEvent.isNotEmpty()) {
                    processSseEvent(currentEvent.toString())
                    currentEvent.clear()
                }
            } else {
                if (currentEvent.isNotEmpty()) currentEvent.append('\n')
                currentEvent.append(line)
            }
        }

        // Dispatch any remaining partial event
        if (currentEvent.isNotEmpty()) {
            processSseEvent(currentEvent.toString())
        }
    }

    /**
     * Parses a complete SSE event and, if it contains a valid JSON-RPC
     * notification, emits it to [_notifications].
     */
    private suspend fun processSseEvent(raw: String) {
        val parsed = SseEventParser.parse(raw) ?: return
        if (parsed.data.isEmpty()) return

        val element = runCatching { json.parseToJsonElement(parsed.data) }.getOrNull() ?: return
        val obj = element as? JsonObject ?: return

        // Only handle notifications (has method but no id)
        if (obj["method"] != null && obj["id"] == null) {
            val notification: JsonRpcNotification<JsonElement> =
                runCatching { json.decodeFromJsonElement<JsonRpcNotification<JsonElement>>(obj) }.getOrNull() ?: return
            _notifications.emit(notification)
        }
    }

    private fun captureSessionId(response: HttpResponse) {
        response.headers[Defaults.MCP_SESSION_ID_HEADER]?.let { sessionId = it }
    }

    private fun notifyCancelledAsync(requestId: Int) {
        val params = CancelledNotificationParams(requestId)
        val paramsElement = json.encodeToJsonElement(serializer<CancelledNotificationParams>(), params)
        val job = backgroundScope.launch {
            runCatching {
                sendNotification(
                    JsonRpcRequest(
                        id = 0,
                        method = McpMethods.NOTIFICATIONS_CANCELLED,
                        params = paramsElement,
                    )
                )
            }
        }
        synchronized(cancellationJobs) {
            cancellationJobs.add(job)
        }
        job.invokeOnCompletion {
            synchronized(cancellationJobs) {
                cancellationJobs.remove(job)
            }
        }
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
}

/**
 * Lightweight SSE event parser supporting the W3C Server-Sent Events grammar.
 */
internal data class SseEvent(
    val id: String? = null,
    val event: String? = null,
    val data: String = "",
    val retry: Long? = null,
)

/**
 * Parser for individual SSE event strings.
 */
internal object SseEventParser {
    fun parse(raw: String): SseEvent? {
        if (raw.isBlank()) return null

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