package io.github.yeyi.agent.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Transport implementation using stdio (subprocess).
 *
 * This transport spawns a child process and communicates with it via stdin/stdout
 * per the MCP stdio spec: messages are newline-delimited UTF-8 JSON, stderr is
 * used by the server for human-readable logging and must be drained continuously
 * to prevent the OS pipe buffer from filling and deadlocking the child.
 *
 * The process is started lazily on first use. [close] performs a graceful
 * three-stage shutdown (close stdin → wait/SIGTERM → SIGKILL) per the spec.
 *
 * Resource ownership: the caller is responsible for invoking [close] when the
 * transport is no longer needed. Parent coroutine cancellation does NOT
 * automatically destroy the child process — [McpRegistry.unregisterAll] is
 * the canonical cleanup path.
 */
public class StdioTransport(
    private val command: List<String>,
    private val workingDirectory: String? = null,
    private val requestTimeoutMillis: Long = 30_000,
    private val stderrHandler: (String) -> Unit = {},
) : McpTransport {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val startMutex = Mutex()
    private val requestMutex = Mutex()

    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private var stdoutReader: BufferedReader? = null
    private var stderrReader: BufferedReader? = null
    private var stderrJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Detached scope used to dispatch notifications/cancelled even after the
    // caller's coroutine has been cancelled. Survives the caller's lifetime so
    // the cancel notification can be flushed to the server.
    private val cancellationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val notificationsSharedFlow = MutableSharedFlow<JsonRpcNotification<JsonElement>>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    override val notifications: Flow<JsonRpcNotification<JsonElement>> =
        notificationsSharedFlow.asSharedFlow()

    override suspend fun send(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> {
        // The id is provided by the caller (McpClient). Transport
        // does not allocate IDs; it merely forwards the request and matches
        // the response back to the caller-provided id.
        val id = request.id

        val requestLine = json.encodeToString(request)

        return try {
            withContext(Dispatchers.IO) {
                ensureStarted()
                requestMutex.withLock {
                    val writer = stdinWriter
                        ?: throw RuntimeException("MCP server process not started")
                    val reader = stdoutReader
                        ?: throw RuntimeException("MCP server process not started")

                    try {
                        withTimeout(requestTimeoutMillis) {
                            writer.write(requestLine)
                            writer.newLine()
                            writer.flush()

                            readResponseWithNotifications(reader, id)
                        }
                    } catch (e: TimeoutCancellationException) {
                        throw RuntimeException(
                            "MCP request timed out after ${requestTimeoutMillis}ms", e
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            // Caller (e.g. Agent loop) cancelled this request. Per MCP spec
            // we should send notifications/cancelled so the server can free
            // its in-flight resources, then propagate the cancellation.
            notifyCancelledAsync(id)
            throw e
        }
    }

    override suspend fun sendNotification(notification: JsonRpcNotification<JsonElement>) {
        withContext(Dispatchers.IO) {
            ensureStarted()
            requestMutex.withLock {
                val writer = stdinWriter
                    ?: throw RuntimeException("MCP server process not started")
                writer.write(json.encodeToString(notification))
                writer.newLine()
                writer.flush()
            }
        }
    }

    private suspend fun ensureStarted() = startMutex.withLock {
        if (process?.isAlive != true) {
            startProcess()
        }
    }

    private fun startProcess() {
        disposeCurrentProcess()

        val builder = ProcessBuilder(command)
        workingDirectory?.let { builder.directory(java.io.File(it)) }
        builder.redirectErrorStream(false)

        val newProcess = builder.start()
        process = newProcess
        stdinWriter = BufferedWriter(OutputStreamWriter(newProcess.outputStream, Charsets.UTF_8))
        stdoutReader = BufferedReader(InputStreamReader(newProcess.inputStream, Charsets.UTF_8))
        stderrReader = BufferedReader(InputStreamReader(newProcess.errorStream, Charsets.UTF_8))

        startStderrDrain()
    }

    private fun disposeCurrentProcess() {
        runCatching { stdinWriter?.close() }
        runCatching { stdoutReader?.close() }
        runCatching { stderrReader?.close() }
        stderrJob?.cancel()
        stderrJob = null
        runCatching { process?.destroyForcibly() }
        process = null
        stdinWriter = null
        stdoutReader = null
        stderrReader = null
    }

    private fun startStderrDrain() {
        val reader = stderrReader ?: return
        stderrJob = scope.launch {
            try {
                reader.useLines { lines ->
                    lines.forEach(stderrHandler)
                }
            } catch (_: CancellationException) {
                // expected when close() cancels the job
            } catch (_: Throwable) {
                // stderr drain errors are non-fatal
            }
        }
    }

    private fun notifyCancelledAsync(requestId: Int) {
        val params = CancelledNotificationParams(requestId)
        val paramsElement =
            json.encodeToJsonElement(serializer<CancelledNotificationParams>(), params)
        cancellationScope.launch {
            runCatching {
                sendNotification(
                    JsonRpcNotification(
                        method = McpMethods.NOTIFICATIONS_CANCELLED,
                        params = paramsElement,
                    )
                )
            }
        }
    }

    /**
     * Read from stdout until we find a response with matching [expectedId].
     * Notifications encountered along the way are emitted to [notificationsSharedFlow].
     * This handles the case where the server sends notifications before the response.
     */
    private suspend fun readResponseWithNotifications(
        reader: BufferedReader,
        expectedId: Int,
    ): JsonRpcResponse<JsonElement> {
        while (true) {
            val line = withContext(Dispatchers.IO) {
                reader.readLine()
            } ?: throw RuntimeException("MCP server process terminated")

            val parsed = json.parseToJsonElement(line)

            // Check if this is a notification (no id field)
            if (parsed is JsonObject && parsed["id"] == null) {
                val notification: JsonRpcNotification<JsonElement> =
                    json.decodeFromJsonElement(parsed)
                notificationsSharedFlow.emit(notification)
                continue
            }

            // Has id - should be the response
            val response: JsonRpcResponse<JsonElement> = json.decodeFromJsonElement(parsed)
            if (response.id != expectedId) {
                throw RuntimeException(
                    "MCP response ID mismatch: expected $expectedId, got ${response.id}"
                )
            }
            return response
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            // Stage 1: close stdin so the server can detect EOF and exit gracefully.
            runCatching { stdinWriter?.close() }
            stdinWriter = null

            // Stage 2: wait briefly; if still alive, send SIGTERM and wait again.
            val proc = process
            if (proc != null && proc.isAlive) {
                if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                    runCatching { proc.destroy() }
                    if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                        // Stage 3: last resort, force-kill.
                        runCatching { proc.destroyForcibly() }
                    }
                }
            }
            process = null

            // Stop the stderr drain coroutine and release IO resources.
            stderrJob?.cancel()
            stderrJob = null
            runCatching { stdoutReader?.close() }
            runCatching { stderrReader?.close() }
            stdoutReader = null
            stderrReader = null
        }
        cancellationScope.cancel()
    }
}
