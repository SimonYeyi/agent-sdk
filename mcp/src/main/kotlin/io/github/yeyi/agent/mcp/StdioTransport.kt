package io.github.yeyi.agent.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transport implementation using stdio (subprocess).
 *
 * This transport spawns a child process and communicates with it via stdin/stdout.
 * Used for local MCP servers that run as command-line tools.
 *
 * The process is started lazily on first use.
 */
public class StdioTransport(
    private val command: List<String>,
    private val workingDirectory: String? = null,
) : McpTransport {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    private var stdout: BufferedReader? = null
    private var stderr: BufferedReader? = null

    private val requestMutex = Mutex()
    private val nextId = AtomicInteger(1)

    private fun ensureStarted() {
        if (process == null || process?.isAlive != true) {
            startProcess()
        }
    }

    private fun startProcess() {
        val builder = ProcessBuilder(command)
        workingDirectory?.let { builder.directory(java.io.File(it)) }
        builder.redirectErrorStream(false)

        process = builder.start()
        stdin = OutputStreamWriter(process!!.outputStream, Charsets.UTF_8)
        stdout = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))
        stderr = BufferedReader(InputStreamReader(process!!.errorStream, Charsets.UTF_8))
    }

    override suspend fun send(request: JsonRpcRequest): JsonRpcResponse {
        val id = nextId.getAndIncrement()

        val paramsJson = request.params?.let { json.encodeToString(it) } ?: "null"
        val requestLine =
            """{"jsonrpc":"2.0","id":$id,"method":"${request.method}","params":$paramsJson}"""

        return withContext(Dispatchers.IO) {
            ensureStarted()
            requestMutex.withLock {
                stdin?.write(requestLine)
                stdin?.write("\n")
                stdin?.flush()

                val responseLine = stdout?.readLine()
                    ?: throw RuntimeException("MCP server process terminated")

                val response = json.decodeFromString<JsonRpcResponse>(responseLine)
                if (response.id != id) {
                    throw RuntimeException("MCP response ID mismatch: expected $id, got ${response.id}")
                }
                response
            }
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { stdin?.close() }
            runCatching { stdout?.close() }
            runCatching { stderr?.close() }
            runCatching { process?.destroyForcibly() }
        }
    }
}