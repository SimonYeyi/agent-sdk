package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.mcp.fixture.FixtureMcpServer
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StdioTransportTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun spawnFixtureTransport(
        serverClass: String = "io.github.yeyi.agent.mcp.fixture.FixtureMcpServer",
        extraJvmArgs: List<String> = emptyList(),
        stderrHandler: (String) -> Unit = {},
    ): StdioTransport {
        val javaBin = "${System.getProperty("java.home")}${File.separator}bin${File.separator}java"
        val classpath = System.getProperty("java.class.path")
        return StdioTransport(
            command = buildList {
                add(javaBin)
                addAll(extraJvmArgs)
                addAll(listOf("-cp", classpath))
                add("io.github.yeyi.agent.mcp.fixture.McpStdioServerMain")
                add(serverClass)
            },
            stderrHandler = stderrHandler,
        )
    }

    @Test
    fun `initialize request gets InitializeResult response through subprocess`() = runBlocking {
        val transport = spawnFixtureTransport()
        try {
            val params = InitializeParams(
                protocolVersion = McpServer.SUPPORTED_PROTOCOL_VERSION,
                capabilities = ClientCapabilities(),
                clientInfo = ClientInfo("test-client", "0.0.1"),
            )
            val request = JsonRpcRequest<JsonElement>(
                id = 1,
                method = McpMethods.INITIALIZE,
                params = json.encodeToJsonElement(serializer<InitializeParams>(), params),
            )

            val response = transport.send(request)

            assertNull(response.error, "expected no JSON-RPC error")
            val result = json.decodeFromJsonElement(serializer<InitializeResult>(), response.result!!)
            assertEquals(McpServer.SUPPORTED_PROTOCOL_VERSION, result.protocolVersion)
            assertEquals(FixtureMcpServer.SERVER_NAME, result.serverInfo.name)
        } finally {
            transport.close()
        }
    }

    @Test
    fun `transport receives notification emitted by subprocess server`() = runBlocking {
        val transport = spawnFixtureTransport(
            serverClass = "io.github.yeyi.agent.mcp.fixture.NotifyingFixtureMcpServer",
        )
        try {
            val notificationDeferred: Deferred<JsonRpcNotification<JsonElement>> = async {
            transport.notifications.first()
        }
            // Send initialize; the fixture will emit a notification as a side effect.
            val params = InitializeParams(
                protocolVersion = McpServer.SUPPORTED_PROTOCOL_VERSION,
                capabilities = ClientCapabilities(),
                clientInfo = ClientInfo("test-client", "0.0.1"),
            )
            val request = JsonRpcRequest<JsonElement>(
                id = 1,
                method = McpMethods.INITIALIZE,
                params = json.encodeToJsonElement(serializer<InitializeParams>(), params),
            )
            val response = transport.send(request)
            assertNotNull(response.result, "expected initialize response")

            val notification = withTimeout(5_000) { notificationDeferred.await() }
            assertEquals(McpMethods.NOTIFICATIONS_PROGRESS, notification.method)
        } finally {
            transport.close()
        }
    }

    @Test
    fun `server transport invokes onClientNotification callback when client sends notification`() = runBlocking {
        val transport = spawnFixtureTransport(
            serverClass = "io.github.yeyi.agent.mcp.fixture.CallbackFixtureMcpServer",
        )
        try {
            val initParams = InitializeParams(
                protocolVersion = McpServer.SUPPORTED_PROTOCOL_VERSION,
                capabilities = ClientCapabilities(),
                clientInfo = ClientInfo("test-client", "0.0.1"),
            )
            val initRequest = JsonRpcRequest<JsonElement>(
                id = 1,
                method = McpMethods.INITIALIZE,
                params = json.encodeToJsonElement(serializer<InitializeParams>(), initParams),
            )
            val initResponse = transport.send(initRequest)
            assertNotNull(initResponse.result, "expected initialize response before notification")

            val sent = JsonRpcNotification<JsonElement>(method = McpMethods.NOTIFICATIONS_INITIALIZED)
            val receivedDeferred: Deferred<JsonRpcNotification<JsonElement>> = async {
                transport.notifications.first()
            }
            transport.sendNotification(sent)

            val received = withTimeout(5_000) { receivedDeferred.await() }
            assertEquals(McpMethods.NOTIFICATIONS_INITIALIZED, received.method)
        } finally {
            transport.close()
        }
    }

    @Test
    fun `transport send throws IllegalStateException when subprocess has died`() = runBlocking {
        val transport = spawnFixtureTransport(
            serverClass = "io.github.yeyi.agent.mcp.fixture.SelfExitFixtureMcpServer",
        )
        try {
            val params = InitializeParams(
                protocolVersion = McpServer.SUPPORTED_PROTOCOL_VERSION,
                capabilities = ClientCapabilities(),
                clientInfo = ClientInfo("test-client", "0.0.1"),
            )
            val initRequest = JsonRpcRequest<JsonElement>(
                id = 1,
                method = McpMethods.INITIALIZE,
                params = json.encodeToJsonElement(serializer<InitializeParams>(), params),
            )
            val initResponse = transport.send(initRequest)
            assertNotNull(initResponse.result, "expected initialize response before self-exit")

            delay(500)

            val pingRequest = JsonRpcRequest<JsonElement>(id = 2, method = McpMethods.PING)
            assertFailsWith<IllegalStateException> {
                transport.send(pingRequest)
                Unit
            }
            Unit
        } finally {
            transport.close()
        }
    }
}
