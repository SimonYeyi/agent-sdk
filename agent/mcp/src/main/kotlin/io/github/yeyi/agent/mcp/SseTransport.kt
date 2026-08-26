package io.github.yeyi.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMessageBuilder
import io.ktor.http.contentType
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
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

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
 * @param endpoint The MCP server endpoint URL.
 * @param protocolVersion The MCP protocol version.
 * @param enableNotifications Whether to establish an SSE connection for server-to-client notifications.
 * @param httpClient External [HttpClient] instance. Caller is responsible for
 *   configuring timeouts, SSL, and any other settings as needed. If not provided,
 *   a default client with 120s request timeout is created internally.
 */
public class SseTransport(
    private val endpoint: String,
    private val httpHeaders: Map<String, String>? = null,
    private val enableNotifications: Boolean = false,
    httpClient: HttpClient? = null
) : McpTransport {
    private object Defaults {
        const val SSE_RECONNECT_DELAY_MS = 5000L
        const val MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
        const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"
    }

    private val protocolVersion: String = McpServer.SUPPORTED_PROTOCOL_VERSION
    private val httpClient = httpClient ?: HttpClient { expectSuccess = true }
    private val isDefaultHttpClient = httpClient == null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Volatile
    private var sessionId: String? = null

    private var sseJob: Job? = null

    private val cancellationJobs = mutableListOf<Job>()

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val notificationsSharedFlow = MutableSharedFlow<JsonRpcNotification<JsonElement>>(
        replay = 0,
        extraBufferCapacity = 256,
    )

    override val notifications: Flow<JsonRpcNotification<JsonElement>> =
        notificationsSharedFlow.asSharedFlow()

    override suspend fun send(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> {
        val body = json.encodeToString(request)

        try {
            val response: HttpResponse = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                withCommHeaders()
                header(
                    HttpHeaders.Accept,
                    "${ContentType.Application.Json}, ${ContentType.Text.EventStream}"
                )
                header(Defaults.MCP_PROTOCOL_VERSION_HEADER, protocolVersion)
                sessionId?.let { header(Defaults.MCP_SESSION_ID_HEADER, it) }
                setBody(body)
            }

            if (request.method == McpMethods.INITIALIZE && enableNotifications) {
                startSseConnection()
            }

            response.headers[Defaults.MCP_SESSION_ID_HEADER]?.let { sessionId = it }

            val contentType = response.contentType()
            return if (contentType != null && ContentType.Text.EventStream.match(contentType)) {
                val channel = response.bodyAsChannel()
                readSseEvents(channel, request.id)!!
            } else {
                val bodyText = response.bodyAsText()
                parseJsonResponse(bodyText, request.id)
            }
        } catch (e: CancellationException) {
            notifyCancelledAsync(request.id)
            throw e
        }
    }

    override suspend fun sendNotification(notification: JsonRpcNotification<JsonElement>) {
        val body = json.encodeToString(notification)

        httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            withCommHeaders()
            header(
                HttpHeaders.Accept,
                "${ContentType.Application.Json}, ${ContentType.Text.EventStream}"
            )
            header(Defaults.MCP_PROTOCOL_VERSION_HEADER, protocolVersion)
            sessionId?.let { header(Defaults.MCP_SESSION_ID_HEADER, it) }
            setBody(body)
        }
    }

    override suspend fun close() {
        sseJob?.cancel()
        sseJob = null

        cancellationJobs.forEach { it.cancel() }
        cancellationJobs.clear()

        backgroundScope.cancel()

        if (isDefaultHttpClient) runCatching { httpClient.close() }
    }

    private fun startSseConnection() {
        sseJob = backgroundScope.launch {
            while (isActive) {
                try {
                    val response: HttpResponse = httpClient.get(endpoint) {
                        accept(ContentType.Text.EventStream)
                        withCommHeaders()
                        header(Defaults.MCP_PROTOCOL_VERSION_HEADER, protocolVersion)
                        sessionId?.let { header(Defaults.MCP_SESSION_ID_HEADER, it) }
                    }

                    val channel = response.bodyAsChannel()
                    readSseEvents(channel)
                } catch (e: Exception) {
                    if (e is IOException || e is ServerResponseException) {
                        log.warn("SSE connection failed, retrying...", e)
                        delay(Defaults.SSE_RECONNECT_DELAY_MS)
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    private suspend fun readSseEvents(
        channel: io.ktor.utils.io.ByteReadChannel,
        expectedId: Int? = null
    ): JsonRpcResponse<JsonElement>? {
        val currentEvent = StringBuilder()

        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break

            if (line.isEmpty()) {
                if (currentEvent.isNotEmpty()) {
                    val result = processSseEvent(currentEvent.toString(), expectedId)
                    if (result != null) return result
                    currentEvent.clear()
                }
            } else {
                if (currentEvent.isNotEmpty()) currentEvent.append('\n')
                currentEvent.append(line)
            }
        }

        if (currentEvent.isNotEmpty()) {
            val result = processSseEvent(currentEvent.toString(), expectedId)
            if (result != null) return result
        }

        if (expectedId != null) {
            throw RuntimeException("SSE response stream ended without finding response for id $expectedId")
        }
        return null
    }

    private fun processSseEvent(raw: String, expectedId: Int?): JsonRpcResponse<JsonElement>? {
        val parsed = SseEventParser.parse(raw) ?: return null
        if (parsed.data.isEmpty()) return null
        val element =
            runCatching { json.parseToJsonElement(parsed.data) }.getOrNull() ?: return null
        val obj = element as? JsonObject ?: return null

        when {
            obj["method"] != null && obj["id"] == null -> {
                val notification = json.decodeFromJsonElement<JsonRpcNotification<JsonElement>>(obj)
                notificationsSharedFlow.tryEmit(notification)
                return null
            }

            else -> {
                val response =
                    runCatching { json.decodeFromJsonElement<JsonRpcResponse<JsonElement>>(obj) }.getOrNull()
                        ?: return null
                if (expectedId != null && response.id != expectedId) {
                    throw RuntimeException("MCP response ID mismatch: expected $expectedId, got ${response.id}")
                }
                return response
            }
        }
    }

    private fun notifyCancelledAsync(requestId: Int) {
        val params = CancelledNotificationParams(requestId)
        val paramsElement =
            json.encodeToJsonElement(CancelledNotificationParams.serializer(), params)
        val job = backgroundScope.launch {
            runCatching {
                sendNotification(
                    JsonRpcNotification(
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

    private fun HttpMessageBuilder.withCommHeaders() {
        httpHeaders?.forEach { header(it.key, it.value) }
    }
}

internal data class SseEvent(
    val id: String? = null,
    val event: String? = null,
    val data: String = "",
    val retry: Long? = null,
)

internal object SseEventParser {
    fun parse(raw: String): SseEvent? {
        if (raw.isBlank()) return null

        var id: String? = null
        var event: String? = null
        val data = StringBuilder()
        var retry: Long? = null

        for (line in raw.split('\n')) {
            if (line.isEmpty()) continue
            if (line.startsWith(':')) continue

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
