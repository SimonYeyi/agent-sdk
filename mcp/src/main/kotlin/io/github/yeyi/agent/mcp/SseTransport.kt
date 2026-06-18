package io.github.yeyi.agent.mcp

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transport implementation using Server-Sent Events (SSE) over HTTP.
 *
 * This transport communicates with remote MCP servers via HTTP POST requests
 * and receives responses via SSE streams.
 */
public class SseTransport(
    private val endpoint: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : McpTransport {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO)
    private val nextId = AtomicInteger(1)

    override suspend fun send(request: JsonRpcRequest): JsonRpcResponse {
        val id = nextId.getAndIncrement()

        val paramsJson = request.params?.let { json.encodeToString(it) } ?: "null"
        val body = """{"jsonrpc":"2.0","id":$id,"method":"${request.method}","params":$paramsJson}"""

        return withContext(Dispatchers.IO) {
            val response: HttpResponse = client.post(endpoint) {
                contentType(ContentType.Application.Json)
                accept(ContentType.parse("application/json, text/event-stream"))
                setBody(body)
                extraHeaders.forEach { (key, value) ->
                    header(key, value)
                }
            }

            if (!response.status.isSuccess()) {
                throw RuntimeException("HTTP error: ${response.status}")
            }

            parseSseResponse(response.bodyAsText(), id)
        }
    }

    private fun parseSseResponse(body: String, expectedId: Int): JsonRpcResponse {
        val lines = body.lines()
        var jsonContent = ""

        for (line in lines) {
            if (line.startsWith("data:")) {
                jsonContent = line.removePrefix("data:").trim()
                if (jsonContent.isNotEmpty() && jsonContent != "[DONE]") {
                    break
                }
            }
        }

        if (jsonContent.isEmpty()) {
            throw RuntimeException("No data received in SSE response")
        }

        val response = json.decodeFromString<JsonRpcResponse>(jsonContent)
        if (response.id != expectedId) {
            throw RuntimeException("MCP response ID mismatch: expected $expectedId, got ${response.id}")
        }
        return response
    }

    override suspend fun close() {
        runCatching { client.close() }
    }
}