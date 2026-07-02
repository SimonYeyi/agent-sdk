package io.gateway.engine

import io.gateway.api.AgentRunner
import io.gateway.api.ConcurrencyController
import io.gateway.api.DeliveryRouter
import io.gateway.api.GatewayEngine
import io.gateway.api.GatewaySessionManager
import io.gateway.api.HookPipeline
import io.gateway.api.PlatformAdapter
import io.gateway.model.GatewayConfig
import io.gateway.model.GatewayError
import io.gateway.model.GatewaySession
import io.gateway.model.GatewayState
import io.gateway.model.IncomingMessage
import io.gateway.model.MessageContent
import io.gateway.model.OutgoingContent
import io.gateway.model.PlatformId
import io.gateway.model.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.gateway.util.gatewayLog
import kotlinx.datetime.Clock
import java.util.concurrent.ConcurrentHashMap

internal class DefaultGatewayEngine(
    override val config: GatewayConfig = GatewayConfig()
) : GatewayEngine {

    private val log = gatewayLog("DefaultGatewayEngine")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var sessionManager: GatewaySessionManager = InMemoryGatewaySessionManager()
    private lateinit var agentRunner: AgentRunner
    private var hookPipeline: HookPipeline = DefaultHookPipeline()
    private var deliveryRouter: DeliveryRouter = DefaultDeliveryRouter()
    private var concurrencyController: ConcurrencyController =
        DefaultConcurrencyController(config.maxConcurrentSessions)

    private val adapters = ConcurrentHashMap<PlatformId, PlatformAdapter>()
    private val processingJobs = ConcurrentHashMap<String, Job>()
    private val pendingMessages = ConcurrentHashMap<String, MutableList<IncomingMessage>>()
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()

    private val _state = MutableStateFlow(GatewayState())
    private val _errors = MutableSharedFlow<GatewayError>(extraBufferCapacity = 100)

    private var totalMessages = 0L
    private var totalErrors = 0L

    override val isRunning: Boolean
        get() = _state.value.isRunning

    override fun setSessionManager(manager: GatewaySessionManager) {
        this.sessionManager = manager
    }

    override fun setAgentRunner(runner: AgentRunner) {
        this.agentRunner = runner
    }

    override fun setHookPipeline(pipeline: HookPipeline) {
        this.hookPipeline = pipeline
    }

    override fun registerHook(hook: HookPipeline.Hook) {
        hookPipeline.register(hook)
    }

    override fun registerAdapter(adapter: PlatformAdapter) {
        adapters[adapter.platformId] = adapter
        deliveryRouter.registerAdapter(adapter)

        adapter.onMessageReceived { message ->
            log.info("Received $message")
            scope.launch {
                handleIncomingMessage(message)
            }
        }

        adapter.onConnectionStateChanged { state ->
            val current = _state.value.connectedPlatforms.toMutableSet()
            if (state == io.gateway.model.ConnectionState.CONNECTED) {
                current.add(adapter.platformId)
            } else {
                current.remove(adapter.platformId)
            }
            _state.value = _state.value.copy(connectedPlatforms = current)
        }

        adapter.onError { error ->
            scope.launch {
                emitError(
                    platform = error.platform,
                    error = error.error
                )
            }
        }
    }

    override fun unregisterAdapter(platformId: PlatformId) {
        adapters.remove(platformId)
        deliveryRouter.unregisterAdapter(platformId)
    }

    override fun getAdapter(platformId: PlatformId): PlatformAdapter? =
        adapters[platformId]

    override fun getAdapters(): List<PlatformAdapter> =
        adapters.values.toList()

    override suspend fun start() {
        if (isRunning) return

        // 清除上次进程中断遗留的标记
        sessionManager.getActiveSessions().forEach { session ->
            if (session.isProcessing) {
                sessionManager.markProcessingComplete(session.key)
            }
        }

        hookPipeline.run(
            HookPipeline.Event.ON_START,
            HookPipeline.Context(event = HookPipeline.Event.ON_START)
        )

        val connectionJobs = adapters.values.map { adapter ->
            scope.launch {
                val result = adapter.connect()
                if (result is io.gateway.model.ConnectResult.Failure) {
                    emitError(
                        platform = adapter.platformId,
                        error = "Connection failed: ${result.error}"
                    )
                } else {
                    hookPipeline.run(
                        HookPipeline.Event.ON_PLATFORM_CONNECT,
                        HookPipeline.Context(
                            event = HookPipeline.Event.ON_PLATFORM_CONNECT,
                            platform = adapter.platformId
                        )
                    )
                }
            }
        }

        connectionJobs.joinAll()

        _state.value = _state.value.copy(
            isRunning = true,
            startedAt = Clock.System.now()
        )
    }

    override suspend fun stop() {
        if (!isRunning) return

        processingJobs.values.forEach { it.cancel() }
        processingJobs.clear()

        adapters.values.forEach { it.disconnect() }

        hookPipeline.run(
            HookPipeline.Event.ON_STOP,
            HookPipeline.Context(event = HookPipeline.Event.ON_STOP)
        )

        _state.value = _state.value.copy(isRunning = false)
    }

    private fun getSessionMutex(sessionKey: String): Mutex =
        sessionMutexes.getOrPut(sessionKey) { Mutex() }

    private suspend fun handleIncomingMessage(message: IncomingMessage) {
        val sessionKey = message.source.sessionKey()

        totalMessages++
        updateStats()

        val hookResult = hookPipeline.run(
            HookPipeline.Event.BEFORE_RECEIVE,
            HookPipeline.Context(
                event = HookPipeline.Event.BEFORE_RECEIVE,
                message = message,
                platform = message.source.platform
            )
        )

        val actualMessage = when (hookResult) {
            is HookPipeline.Result.Halt -> return
            is HookPipeline.Result.ModifyMessage -> hookResult.newMessage
            else -> message
        }

        onProcessingCallback(actualMessage, null)

        getSessionMutex(sessionKey).withLock {
            val session = sessionManager.getOrCreateSession(actualMessage.source)

            if (session.isProcessing) {
                // 正在处理，加入 pending 队列，取消旧的
                val pending = pendingMessages.getOrPut(sessionKey) { mutableListOf() }
                pending.add(actualMessage)
                processingJobs[sessionKey]?.cancel()
                log.debug("Session is processing, message pending...")
                return
            }

            // 尝试获取全局并发槽
            if (!concurrencyController.acquire()) {
                val errorMsg = "Concurrency limit reached, message dropped"
                onProcessingCallback(actualMessage, AgentRunner.Result.Failure(errorMsg))
                emitError(
                    platform = actualMessage.source.platform,
                    sessionKey = sessionKey,
                    error = errorMsg
                )
                return
            }

            // 成功获取并发槽，标记处理中
            sessionManager.markProcessing(sessionKey)
            updateStats()

            val job = scope.launch {
                val result = processMessageSafely(actualMessage, session)

                onProcessingCallback(actualMessage, result)

                // 无论成功/失败/取消，都释放资源
                concurrencyController.release()
                sessionManager.markProcessingComplete(sessionKey)
                processingJobs.remove(sessionKey)
                updateStats()
                processPendingMessages(sessionKey)
            }

            processingJobs[sessionKey] = job
        }
    }

    private fun onProcessingCallback(
        message: IncomingMessage,
        result: AgentRunner.Result?
    ) {
        scope.launch {
            val adapter = adapters[message.source.platform]
            try {
                if (result == null) {
                    adapter?.onProcessingStart(message.id.value)
                } else {
                    adapter?.onProcessingComplete(
                        message.id.value,
                        result is AgentRunner.Result.Success
                    )
                }
            } catch (e: Exception) {
                val callbackName = "onProcessing${if (result == null) "Start" else "Complete"}"
                log.warn("$adapter.${callbackName}} error: ${message.id}", e)
            }
        }
    }

    private suspend fun processPendingMessages(sessionKey: String) {
        val pending = pendingMessages[sessionKey] ?: return
        if (pending.isEmpty()) return

        val message = pending.removeAt(0)
        if (pending.isEmpty()) {
            pendingMessages.remove(sessionKey)
        }

        scope.launch {
            handleIncomingMessage(message)
        }
    }

    private suspend fun processMessageSafely(
        message: IncomingMessage,
        session: GatewaySession
    ): AgentRunner.Result {
        hookPipeline.run(
            HookPipeline.Event.AFTER_RECEIVE,
            HookPipeline.Context(
                event = HookPipeline.Event.AFTER_RECEIVE,
                session = session,
                message = message,
                platform = message.source.platform
            )
        )

        val validateResult = hookPipeline.run(
            HookPipeline.Event.BEFORE_VALIDATE,
            HookPipeline.Context(
                event = HookPipeline.Event.BEFORE_VALIDATE,
                session = session,
                message = message,
                platform = message.source.platform
            )
        )

        if (validateResult is HookPipeline.Result.Halt) {
            return AgentRunner.Result.Failure("Validate failed.")
        }

        hookPipeline.run(
            HookPipeline.Event.AFTER_VALIDATE,
            HookPipeline.Context(
                event = HookPipeline.Event.AFTER_VALIDATE,
                session = session,
                message = message,
                platform = message.source.platform
            )
        )

        if (config.enableTypingIndicator) {
            try {
                adapters[message.source.platform]?.sendTypingIndicator(message.source.chatId)
            } catch (e: Exception) {
                log.warn("Failed to send typing indicator for chat ${message.source.chatId}", e)
            }
        }

        hookPipeline.run(
            HookPipeline.Event.BEFORE_AGENT,
            HookPipeline.Context(
                event = HookPipeline.Event.BEFORE_AGENT,
                session = session,
                message = message,
                platform = message.source.platform
            )
        )

        val agentResult = try {
            agentRunner.process(message = message, session = session)
        } catch (e: Exception) {
            log.warn("Agent run failed.", e)
            AgentRunner.Result.Failure(error = e.message ?: "Unknown error", exception = e)
        }

        hookPipeline.run(
            HookPipeline.Event.AFTER_AGENT,
            HookPipeline.Context(
                event = HookPipeline.Event.AFTER_AGENT,
                session = session,
                message = message,
                platform = message.source.platform
            )
        )

        when (agentResult) {
            is AgentRunner.Result.Success -> {
                sendResponse(message, session, agentResult.responseContent)
                sessionManager.updateSessionStats(
                    sessionKey = session.key,
                    messageCountDelta = 1,
                    turnCountDelta = 1
                )
            }

            is AgentRunner.Result.Failure -> {
                emitError(
                    platform = message.source.platform,
                    sessionKey = session.key,
                    error = "Agent failure: ${agentResult.error}",
                    throwable = agentResult.exception
                )
                totalErrors++
                updateStats()
            }

            is AgentRunner.Result.NeedMoreInput -> {
                sendResponse(
                    message, session,
                    MessageContent.Text(agentResult.prompt)
                )
            }

            is AgentRunner.Result.Interrupted,
            AgentRunner.Result.Silent -> {
                // 静默处理，不发送响应
            }
        }

        return agentResult
    }

    private suspend fun sendResponse(
        incomingMessage: IncomingMessage,
        session: GatewaySession,
        responseContent: MessageContent
    ) {
        val outgoingContent = when (responseContent) {
            is MessageContent.Text -> OutgoingContent.Text(responseContent.text)
            is MessageContent.Image -> {
                val url = responseContent.urls.firstOrNull()
                if (url != null) {
                    OutgoingContent.Image(url = url, caption = responseContent.caption)
                } else {
                    OutgoingContent.Text(responseContent.caption ?: "Image (no URL)")
                }
            }

            is MessageContent.Audio -> OutgoingContent.Audio(responseContent.url)
            is MessageContent.Document -> OutgoingContent.Document(
                url = responseContent.url,
                fileName = responseContent.fileName
            )

            else -> OutgoingContent.Text("Unsupported message type")
        }

        val hookResult = hookPipeline.run(
            HookPipeline.Event.BEFORE_SEND,
            HookPipeline.Context(
                event = HookPipeline.Event.BEFORE_SEND,
                session = session,
                message = incomingMessage,
                platform = incomingMessage.source.platform
            )
        )

        if (hookResult is HookPipeline.Result.Halt) {
            return
        }

        val actualContent = when (hookResult) {
            is HookPipeline.Result.ModifyResponse -> hookResult.newResponse
            else -> outgoingContent
        }

        var sendResult: SendResult = SendResult.Failure(
            error = "Unknown send error",
            retryable = false
        )

        for (attempt in 0..config.messageRetryCount) {
            sendResult = deliveryRouter.deliver(
                platform = incomingMessage.source.platform,
                chatId = incomingMessage.source.chatId,
                content = actualContent,
                replyTo = incomingMessage.id.value,
                threadId = incomingMessage.source.threadId
            )

            if (sendResult is SendResult.Success) {
                break
            }

            val failure = sendResult as SendResult.Failure
            if (!failure.retryable || attempt >= config.messageRetryCount) {
                break
            }
            kotlinx.coroutines.delay(config.messageRetryDelayMs)
        }

        if (sendResult is SendResult.Failure) {
            hookPipeline.run(
                HookPipeline.Event.ON_SEND_FAILED,
                HookPipeline.Context(
                    event = HookPipeline.Event.ON_SEND_FAILED,
                    session = session,
                    message = incomingMessage,
                    platform = incomingMessage.source.platform,
                    sendResult = sendResult
                )
            )
        }

        hookPipeline.run(
            HookPipeline.Event.AFTER_SEND,
            HookPipeline.Context(
                event = HookPipeline.Event.AFTER_SEND,
                session = session,
                message = incomingMessage,
                platform = incomingMessage.source.platform,
                sendResult = sendResult
            )
        )
    }

    override suspend fun sendMessage(
        platform: PlatformId,
        chatId: String,
        content: OutgoingContent,
        replyTo: String?,
        threadId: String?
    ): SendResult {
        return deliveryRouter.deliver(platform, chatId, content, replyTo, threadId)
    }

    private suspend fun emitError(
        platform: PlatformId? = null,
        sessionKey: String? = null,
        error: String,
        throwable: Throwable? = null
    ) {
        val gatewayError = GatewayError(
            platform = platform,
            sessionKey = sessionKey,
            error = error,
            exceptionClass = throwable?.javaClass?.name
        )
        _errors.emit(gatewayError)

        hookPipeline.run(
            HookPipeline.Event.ON_ERROR,
            HookPipeline.Context(
                event = HookPipeline.Event.ON_ERROR,
                platform = platform,
                error = throwable
            )
        )
    }

    private fun updateStats() {
        _state.value = _state.value.copy(
            totalMessages = totalMessages,
            totalErrors = totalErrors,
            processingSessions = processingJobs.size
        )
    }

    override fun observeState(): Flow<GatewayState> = _state.asStateFlow()

    override fun observeErrors(): Flow<GatewayError> = _errors.asSharedFlow()
}
