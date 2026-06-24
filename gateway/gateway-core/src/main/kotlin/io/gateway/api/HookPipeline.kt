package io.gateway.api

import io.gateway.model.IncomingMessage
import io.gateway.model.OutgoingMessage
import io.gateway.model.OutgoingContent
import io.gateway.model.PlatformId
import io.gateway.model.SendResult
import io.gateway.model.GatewaySession

interface HookPipeline {

    enum class Event {
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

    sealed class Result {
        object Continue : Result()
        data class Halt(val reason: String) : Result()
        data class ModifyMessage(val newMessage: IncomingMessage) : Result()
        data class ModifyResponse(val newResponse: OutgoingContent) : Result()
    }

    data class Context(
        val event: Event,
        val session: GatewaySession? = null,
        val message: IncomingMessage? = null,
        val outgoingMessage: OutgoingMessage? = null,
        val platform: PlatformId? = null,
        val error: Throwable? = null,
        val sendResult: SendResult? = null,
        val metadata: MutableMap<String, String> = mutableMapOf()
    )

    interface Hook {
        val name: String
        val events: Set<Event>
        val priority: Int get() = 100
        suspend fun execute(context: Context): Result
    }

    fun register(hook: Hook)

    fun unregister(hookName: String)

    suspend fun run(event: Event, context: Context): Result

    fun getHooks(): List<Hook>

    fun getHooks(event: Event): List<Hook>
}
