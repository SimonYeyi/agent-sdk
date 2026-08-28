package io.github.yeyi.agent.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Transport implementation using stdio (subprocess).
 *
 * Two independent flows:
 * - A persistent stdout reader drains every server line, classifying each as
 *   a notification (no id, dispatched to [notifications]) or a response
 *   (has id, matched against in-flight requests by id). This decouples
 *   notification dispatch from the request lifecycle: notifications arriving
 *   during idle (no in-flight request) are still delivered.
 * - A [writeMutex] serializes stdin writes so each request/notification line
 *   is flushed atomically. Multiple [send] calls may be in flight concurrently
 *   since responses are matched by id.
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
    private val writeMutex = Mutex()

    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private var stdoutReader: BufferedReader? = null
    private var stderrReader: BufferedReader? = null
    private var stderrJob: Job? = null
    private var readerJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-flight request responses, keyed by the caller-provided request id.
    // Populated by send() before writing the request, drained by the stdout
    // reader when the matching response arrives.
    private val pendingRequests =
        ConcurrentHashMap<Int, CompletableDeferred<JsonRpcResponse<JsonElement>>>()

    private val notificationsSharedFlow = MutableSharedFlow<JsonRpcNotification<JsonElement>>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    override val notifications: Flow<JsonRpcNotification<JsonElement>> =
        notificationsSharedFlow.asSharedFlow()

    override suspend fun send(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> {
        val id = request.id
        val requestLine = json.encodeToString(request)
        val deferred = CompletableDeferred<JsonRpcResponse<JsonElement>>()

        pendingRequests[id] = deferred

        try {
            withContext(Dispatchers.IO) {
                ensureStarted()
                writeMutex.withLock {
                    val writer = stdinWriter!!
                    writer.write(requestLine)
                    writer.newLine()
                    writer.flush()
                }
            }

            return withTimeout(requestTimeoutMillis) {
                deferred.await()
            }
        } finally {
            pendingRequests.remove(id)
        }
    }

    override suspend fun sendNotification(notification: JsonRpcNotification<JsonElement>) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val writer = stdinWriter!!
                writer.write(json.encodeToString(notification))
                writer.newLine()
                writer.flush()
            }
        }
    }

    private suspend fun ensureStarted() = startMutex.withLock {
        val proc = process
        when {
            proc == null -> startProcess()
            !proc.isAlive -> {
                disposeCurrentProcess()
                throw IllegalStateException(
                    "MCP stdio process is no longer alive; caller should reinitialize"
                )
            }
        }
    }

    private fun startProcess() {
        val builder = ProcessBuilder(command)
        workingDirectory?.let { builder.directory(java.io.File(it)) }
        builder.redirectErrorStream(false)

        val newProcess = builder.start()
        process = newProcess
        stdinWriter = BufferedWriter(OutputStreamWriter(newProcess.outputStream, Charsets.UTF_8))
        stdoutReader = BufferedReader(InputStreamReader(newProcess.inputStream, Charsets.UTF_8))
        stderrReader = BufferedReader(InputStreamReader(newProcess.errorStream, Charsets.UTF_8))

        startStderrDrain()
        startStdoutReader()
    }

    private fun disposeCurrentProcess() {
        stderrJob?.cancel()
        stderrJob = null
        readerJob?.cancel()
        readerJob = null
        runCatching { stdinWriter?.close() }
        runCatching { stdoutReader?.close() }
        runCatching { stderrReader?.close() }
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

    /**
     * Background reader that continuously drains stdout. Each line is either
     * a server-to-client notification (no id, dispatched to [notifications])
     * or a response (has id, completed against the matching pending Deferred).
     * Notifications arriving while no request is in flight are still delivered.
     */
    private fun startStdoutReader() {
        val reader = stdoutReader ?: return
        readerJob = scope.launch {
            try {
                while (isActive) {
                    val line = withContext(Dispatchers.IO) {
                        reader.readLine()
                    }
                    if (line == null) {
                        failAllPending("MCP server process terminated")
                        break
                    }
                    dispatchLine(line)
                }
            } catch (_: CancellationException) {
                // expected on close()
            }
        }
    }

    private fun dispatchLine(line: String) {
        val parsed = json.parseToJsonElement(line)

        // Notification: no id field. JSON-RPC 2.0 forbids id on notifications,
        // so absence of id is sufficient to identify them.
        if (parsed is JsonObject && parsed["id"] == null) {
            val notification: JsonRpcNotification<JsonElement> =
                json.decodeFromJsonElement(parsed)
            notificationsSharedFlow.tryEmit(notification)
            return
        }

        val response: JsonRpcResponse<JsonElement> = json.decodeFromJsonElement(parsed)
        pendingRequests.remove(response.id)?.complete(response)
        // Orphan response (id not in pending) — silently drop.
    }

    private fun failAllPending(reason: String) {
        val pending = pendingRequests.values.toList()
        pendingRequests.clear()
        pending.forEach { it.completeExceptionally(IllegalStateException(reason)) }
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

            // Stop background reader and stderr drain; release IO resources.
            readerJob?.cancel()
            readerJob = null
            stderrJob?.cancel()
            stderrJob = null
            runCatching { stdoutReader?.close() }
            runCatching { stderrReader?.close() }
            stdoutReader = null
            stderrReader = null
        }
        failAllPending("MCP transport closed")
    }
}
