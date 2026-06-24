package io.gateway.api

import io.gateway.model.GatewaySession
import io.gateway.model.IncomingMessage
import io.gateway.model.MessageContent
import kotlinx.coroutines.flow.Flow

interface AgentRunner {

    sealed class Result {
        data class Success(
            val responseContent: MessageContent,
            val metadata: Map<String, String> = emptyMap()
        ) : Result()

        data class Interrupted(val reason: String = "New message arrived") : Result()

        data class Failure(
            val error: String,
            val exception: Throwable? = null
        ) : Result()

        data class NeedMoreInput(
            val prompt: String,
            val timeoutSeconds: Int? = null
        ) : Result()

        object Silent : Result()
    }

    suspend fun process(message: IncomingMessage, session: GatewaySession): Result

    fun observeStream(sessionKey: String): Flow<String>? = null
}
