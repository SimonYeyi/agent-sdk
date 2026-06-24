package io.gateway.api

import io.gateway.model.IncomingMessage
import io.gateway.model.OutgoingMessage
import io.gateway.model.OutgoingContent
import io.gateway.model.PlatformId
import io.gateway.model.SendResult
import io.gateway.model.GatewaySession

public interface HookPipeline {

    public enum class Event {
        BEFORE_RECEIVE,
        AFTER_RECEIVE,
        BEFORE_VALIDATE,
        AFTER_VALIDATE,
        BEFORE_AGENT,
        AFTER_AGENT,
        BEFORE_SEND,
        AFTER_SEND,
        ON_SEND_FAILED,
        ON_START,
        ON_STOP,
        ON_PLATFORM_CONNECT,
        ON_PLATFORM_DISCONNECT,
        ON_SESSION_CREATE,
        ON_SESSION_DESTROY,
        ON_ERROR
    }

    public sealed class Result {
        public object Continue : Result()
        public data class Halt(val reason: String) : Result()
        public data class ModifyMessage(val newMessage: IncomingMessage) : Result()
        public data class ModifyResponse(val newResponse: OutgoingContent) : Result()
    }

    public data class Context(
        val event: Event,
        val session: GatewaySession? = null,
        val message: IncomingMessage? = null,
        val outgoingMessage: OutgoingMessage? = null,
        val platform: PlatformId? = null,
        val error: Throwable? = null,
        val sendResult: SendResult? = null,
        val metadata: MutableMap<String, String> = mutableMapOf()
    )

    public interface Hook {
        public val name: String
        public val events: Set<Event>
        public val priority: Int get() = 100
        public suspend fun execute(context: Context): Result
    }

    public fun register(hook: Hook)

    public fun unregister(hookName: String)

    public suspend fun run(event: Event, context: Context): Result

    public fun getHooks(): List<Hook>

    public fun getHooks(event: Event): List<Hook>
}
