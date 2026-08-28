package io.github.yeyi.agent.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for McpClient's self-healing behavior when the transport signals a
 * dead/never-started session via IllegalStateException, and the cancellation
 * path's use of sendNotification.
 */
class McpClientSelfHealTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun initializeResponse(id: Int): JsonRpcResponse<JsonElement> = JsonRpcResponse(
        jsonrpc = "2.0",
        id = id,
        result = json.parseToJsonElement(
            """{"protocolVersion":"2025-06-18","serverInfo":{"name":"test","version":"1.0"},"capabilities":{}}"""
        ),
    )

    private fun listToolsResponse(id: Int): JsonRpcResponse<JsonElement> = JsonRpcResponse(
        jsonrpc = "2.0",
        id = id,
        result = json.encodeToJsonElement(
            ListToolsResult.serializer(),
            ListToolsResult(tools = emptyList()),
        ),
    )

    /** Transport that delegates every send to [onSuccess] — tests build the
     *  desired failure pattern by having onSuccess throw. */
    private class ScriptedTransport(
        private val onSuccess: (JsonRpcRequest<JsonElement>) -> JsonRpcResponse<JsonElement>,
    ) : McpTransport {
        val sentRequests: MutableList<JsonRpcRequest<JsonElement>> = mutableListOf()
        val sentNotifications: MutableList<JsonRpcNotification<JsonElement>> = mutableListOf()

        override suspend fun send(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> {
            sentRequests += request
            return onSuccess(request)
        }

        override suspend fun sendNotification(notification: JsonRpcNotification<JsonElement>) {
            sentNotifications += notification
        }

        override val notifications: Flow<JsonRpcNotification<JsonElement>> = emptyFlow()
        override suspend fun close() = Unit
    }

    @Test
    fun `IllegalStateException on a post-init request triggers re-initialize and retries the original request`() = runTest {
        // Simulate: client.initialize() succeeds normally; the transport then
        // reports a dead process for the *next* request (listTools). The retry
        // path should clear initResult, re-handshake, and re-issue listTools.
        var postInitSendCount = 0
        val transport = ScriptedTransport { req ->
            when (req.method) {
                McpMethods.INITIALIZE -> initializeResponse(req.id)
                McpMethods.TOOLS_LIST -> {
                    postInitSendCount++
                    if (postInitSendCount == 1) {
                        // First listTools: process died mid-session
                        throw IllegalStateException("stdio process died")
                    }
                    listToolsResponse(req.id)
                }
                else -> error("unexpected method: ${req.method}")
            }
        }
        val client = McpClient(transport)

        client.initialize()
        val result = client.listTools(null)

        assertEquals(emptyList(), result.tools)

        // Expected send sequence:
        //   1. INITIALIZE → ok (first initialize)
        //   2. TOOLS_LIST → throws ISE (post-init failure)
        //   3. INITIALIZE → ok (re-handshake triggered by ISE catch)
        //   4. TOOLS_LIST → ok (retry of the original request)
        assertEquals(4, transport.sentRequests.size, "expected 4 sends: init + failed listTools + re-init + retry listTools")
        assertEquals(McpMethods.INITIALIZE, transport.sentRequests[0].method)
        assertEquals(McpMethods.TOOLS_LIST, transport.sentRequests[1].method)
        assertEquals(McpMethods.INITIALIZE, transport.sentRequests[2].method)
        assertEquals(McpMethods.TOOLS_LIST, transport.sentRequests[3].method)

        // notifications/initialized sent twice: once for the original handshake,
        // once for the re-handshake.
        val initNotifications = transport.sentNotifications.filter {
            it.method == McpMethods.NOTIFICATIONS_INITIALIZED
        }
        assertEquals(2, initNotifications.size, "expected two notifications/initialized")
    }

    @Test
    fun `IllegalStateException on every post-init send surfaces to caller without looping`() = runTest {
        val transport = ScriptedTransport { req ->
            when (req.method) {
                McpMethods.INITIALIZE -> initializeResponse(req.id)
                McpMethods.TOOLS_LIST -> throw IllegalStateException("stdio process died")
                else -> error("unexpected method: ${req.method}")
            }
        }
        val client = McpClient(transport)
        client.initialize()

        val ex = assertFailsWith<IllegalStateException> {
            client.listTools(null)
        }
        // The retry path bounds itself: one listTools attempt + one re-init + one
        // retry. If the retry also fails, the ISE surfaces. Total sends must be
        // bounded — if it were unbounded we'd see StackOverflowError or hang.
        assertTrue(
            transport.sentRequests.size <= 10,
            "expected bounded retry, got ${transport.sentRequests.size} sends",
        )
        assertTrue("stdio process died" in (ex.message ?: ""))
    }

    @Test
    fun `cancelled request fires notifications-cancelled exactly once on the transport`() = runTest {
        val transport = ScriptedTransport { req ->
            when (req.method) {
                McpMethods.INITIALIZE -> initializeResponse(req.id)
                McpMethods.TOOLS_LIST -> throw CancellationException("caller cancelled")
                else -> error("unexpected method: ${req.method}")
            }
        }
        val client = McpClient(transport)
        client.initialize()

        assertFailsWith<CancellationException> {
            client.listTools(null)
        }

        // notifyCancelledIfOpen dispatches via fire-and-forget launch on
        // Dispatchers.IO (real thread pool, not the test scheduler). Allow the
        // IO coroutine to run before asserting on captured notifications.
        kotlinx.coroutines.runBlocking { delay(50) }

        val cancelled = transport.sentNotifications.filter {
            it.method == McpMethods.NOTIFICATIONS_CANCELLED
        }
        assertEquals(1, cancelled.size, "expected exactly one notifications/cancelled")
        val params = cancelled.single().params as? JsonObject
            ?: error("notifications/cancelled should carry params")
        val requestIdElement = params["requestId"]
        assertNotNull(requestIdElement, "missing requestId in cancelled notification params")
    }

    @Test
    fun `close sets closed flag so a late cancel notification is suppressed`() = runTest {
        val transport = ScriptedTransport { req ->
            when (req.method) {
                McpMethods.INITIALIZE -> initializeResponse(req.id)
                McpMethods.TOOLS_LIST -> throw CancellationException("caller cancelled")
                else -> error("unexpected method: ${req.method}")
            }
        }
        val client = McpClient(transport)
        client.initialize()

        // Close BEFORE the cancelled request — the cancelled notification should
        // be suppressed by the closed flag in notifyCancelledIfOpen.
        client.close()

        assertFailsWith<CancellationException> {
            client.listTools(null)
        }

        // Allow the IO dispatcher a window to run any launched coroutines.
        // With the closed flag set, none should fire.
        kotlinx.coroutines.runBlocking { delay(50) }

        val cancelled = transport.sentNotifications.filter {
            it.method == McpMethods.NOTIFICATIONS_CANCELLED
        }
        assertEquals(0, cancelled.size, "no cancelled notification expected after close()")
    }
}