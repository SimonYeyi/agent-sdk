package io.gateway.platform.telegram

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull

internal class TelegramPoller(
    private val config: TelegramConfig,
    private val httpClient: HttpClient,
    private val json: Json,
    private val messageListener: (JsonObject) -> Unit,
    private val stateListener: (ConnectionState) -> Unit,
    private val errorListener: (PlatformError) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var lastUpdateId: Long = 0

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
                        platform = PlatformId.TELEGRAM,
                        error = "Invalid bot token"
                    )
                )
                stateListener(ConnectionState.ERROR)
                return@launch
            }

            stateListener(ConnectionState.CONNECTED)

            while (isActive && running) {
                try {
                    pollUpdates()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isActive && running) {
                        errorListener(
                            PlatformError(
                                platform = PlatformId.TELEGRAM,
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
            val response = httpClient.get("${config.apiBaseUrl}/bot${config.botToken}/getMe")
            val body = response.bodyAsText()
            val jsonResponse = json.decodeFromString<JsonObject>(body)
            jsonResponse["ok"]?.jsonPrimitive?.booleanOrNull == true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun pollUpdates() {
        val params = buildJsonObject {
            put("offset", lastUpdateId + 1)
            put("limit", config.pollingLimit)
            put("timeout", config.pollingTimeout)
        }

        val url = "${config.apiBaseUrl}/bot${config.botToken}/getUpdates"

        val response = httpClient.post(url) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(params.toString())
        }

        val body = response.bodyAsText()
        val jsonResponse = json.decodeFromString<JsonObject>(body)

        if (jsonResponse["ok"]?.jsonPrimitive?.booleanOrNull == true) {
            val results = jsonResponse["result"]?.jsonArray ?: return
            for (update in results) {
                val updateObj = update.jsonObject ?: continue
                val updateId = updateObj["update_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                if (updateId > lastUpdateId) {
                    lastUpdateId = updateId
                }
                messageListener(updateObj)
            }
        }
    }
}
