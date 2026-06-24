package io.gateway.api

import io.gateway.model.GatewaySession
import io.gateway.model.IncomingMessage
import io.gateway.model.MessageContent
import kotlinx.coroutines.flow.Flow

public interface AgentRunner {

    public sealed class Result {
        public data class Success(
            val responseContent: MessageContent,
            val metadata: Map<String, String> = emptyMap()
        ) : Result()

        public data class Interrupted(val reason: String = "New message arrived") : Result()

        public data class Failure(
            val error: String,
            val exception: Throwable? = null
        ) : Result()

        public data class NeedMoreInput(
            val prompt: String,
            val timeoutSeconds: Int? = null
        ) : Result()

        public object Silent : Result()
    }

    public suspend fun process(message: IncomingMessage, session: GatewaySession): Result

    public fun observeStream(sessionKey: String): Flow<String>? = null
}
