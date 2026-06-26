package io.gateway.platform.weixin

import io.gateway.model.ConnectionState
import io.gateway.model.PlatformError
import io.gateway.model.PlatformId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class WeixinPoller(
    private val config: WeixinConfig,
    private val httpClient: HttpClient,
    private val json: Json,
    private val messageListener: (JsonObject) -> Unit,
    private val stateListener: (ConnectionState) -> Unit,
    private val errorListener: (PlatformError) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var lastCursor: String = ""

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        stateListener(ConnectionState.CONNECTING)

        pollJob = scope.launch {
            if (!validateToken()) {
                errorListener(
                    PlatformError(
                        platform = PlatformId.WEIXIN,
                        error = "Invalid login token"
                    )
                )
                stateListener(ConnectionState.ERROR)
                return@launch
            }

            stateListener(ConnectionState.CONNECTED)

            while (isActive && running) {
                try {
                    pollMessages()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isActive && running) {
                        errorListener(
                            PlatformError(
                                platform = PlatformId.WEIXIN,
                                error = "Poll error: ${e.message}",
                                details = e.javaClass.name
                            )
                        )
                        stateListener(ConnectionState.RECONNECTING)
                        delay(5000)
                        stateListener(ConnectionState.CONNECTED)
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        pollJob?.cancel()
        pollJob = null
        stateListener(ConnectionState.DISCONNECTED)
    }

    private suspend fun validateToken(): Boolean {
        return try {
            val response = httpClient.get("${config.baseUrl}/cgi-bin/bot/get_info") {
                header(HttpHeaders.Authorization, "Bearer ${config.loginToken}")
            }
            val body = response.bodyAsText()
            val jsonResponse = json.decodeFromString<JsonObject>(body)
            jsonResponse["errcode"]?.jsonPrimitive?.content?.toIntOrNull() == 0
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun pollMessages() {
        val body = buildJsonObject {
            put("limit", 100)
            if (lastCursor.isNotBlank()) {
                put("cursor", lastCursor)
            }
            config.routeTag?.let { put("route_tag", it) }
        }

        val url = "${config.baseUrl}/cgi-bin/message/sync"

        val response = httpClient.post(url) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Authorization, "Bearer ${config.loginToken}")
            setBody(body.toString())
        }

        val responseBody = response.bodyAsText()
        val responseJson = json.decodeFromString<JsonObject>(responseBody)

        if (responseJson["errcode"]?.jsonPrimitive?.content?.toIntOrNull() == 0) {
            val messages = responseJson["messages"]?.jsonArray ?: return
            val cursor = responseJson["next_cursor"]?.jsonPrimitive?.content ?: ""
            if (cursor.isNotBlank()) {
                lastCursor = cursor
            }

            for (msg in messages) {
                msg.jsonObject?.let { messageListener(it) }
            }

            if (messages.isEmpty()) {
                delay(2000)
            }
        } else {
            delay(5000)
        }
    }
}
