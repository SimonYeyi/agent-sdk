package io.github.yeyi.agent.demo.team.voice

import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 火山 Seed-ASR 流式 WebSocket 引擎：按住说话（PTT）语音输入的完整编排层。
 *
 * 完全绕开系统 SpeechRecognizer 的 VAD 判停限制：
 * - 服务端 `end_window_size=10000` 提供 10s 长静音容忍，按住期间自然停顿不判停；
 * - 自采集 AudioRecord 16kHz PCM 经二进制帧推流，无续接重启，无系统滴滴提示音；
 * - 每轮结束后断开 WS、下轮重建，避免长连接的陈旧 session 死连接。
 *
 * 单轮编排：
 * 1. 开始采集（pre-roll：建连期间音频先进无界队列缓冲，抵消连接延迟）
 * 2. 连接 WS → 发送 full client request（声明采样率 / end_window_size）
 * 3. 推流采集音频（audio only 帧）直至用户松手
 * 4. 松手后尾音 drain 400ms（读完 AudioRecord 缓冲尾部，避免句尾音频丢失）
 * 5. 发送负包（FLAG_LAST_PACKET 定稿）→ 等待服务端 Final（6s 超时）→ 断连
 *
 * 并发安全：
 * - [sendMutex] 串行化所有 WS 发送（full client request / 音频 / 负包）
 * - [roundJob] 串行化轮次：上一轮未结束不允许新一轮
 * - [activeRoundId] 防旧轮清理误关新轮的采集通道（旧轮 finally 校验轮次归属）
 *
 * 结果归并：interim 期间持续取最长文本上屏；定稿包若只带尾句，仍保留已累积的完整文本。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeedAsrEngine(
    private val scope: CoroutineScope,
    private val client: HttpClient,
    private val microphone: MicrophoneAdapter,
    appKey: String,
) {
    enum class State { Idle, Connecting, Listening, Finalizing }

    /** 引擎状态回调：Connecting → Listening → Finalizing → Idle。 */
    var onStateChange: ((State) -> Unit)? = null

    /** 中间结果回调：按住期间持续触发，文本为当前最长累积结果。 */
    var onInterimResult: ((String) -> Unit)? = null

    /** 定稿结果回调：松手后最终文本，仅在非空白时触发。 */
    var onFinalResult: ((String) -> Unit)? = null

    /** 错误回调：连接失败 / 采集失败 / 协议错误等。 */
    var onError: ((String) -> Unit)? = null

    private val appId = appKey.substringBefore(':')
    private val accessKey = appKey.substringAfter(':')

    private val roundSerial = AtomicInteger(0)
    private val sendMutex = Mutex()

    private var ws: WebSocketSession? = null
    private var roundJob: Job? = null
    private var releaseSignal = CompletableDeferred<Unit>()

    /** 当前活跃轮次的序号；旧轮 finally 据此判断是否仍拥有共享资源（mic / ws / 状态）。 */
    @Volatile
    private var activeRoundId = 0

    private var bestText = ""

    private val micFormat = AudioFormat(
        sampleRateHz = 16_000,
        encoding = AudioFormat.Encoding.PCM_16BIT,
    )

    /** 按下：开启一轮识别；已有活跃轮次则忽略。 */
    suspend fun start() {
        if (roundJob?.isActive == true) return
        roundJob?.cancelAndJoin()
        releaseSignal = CompletableDeferred()
        bestText = ""
        roundJob = scope.launch { runRound() }
    }

    /** 松开：结束推流并等待定稿结果。 */
    fun stop() {
        if (roundJob?.isActive == true) {
            releaseSignal.complete(Unit)
        }
    }

    /** 手势取消：中止本轮，不发送任何文本。 */
    fun cancel() {
        roundJob?.cancel()
    }

    /** 释放引擎：取消进行中的轮次（其 finally 负责回收 mic / ws）。 */
    fun close() {
        roundJob?.cancel()
        roundJob = null
        ws?.cancel()
        ws = null
    }

    private suspend fun runRound() {
        val roundId = roundSerial.incrementAndGet()
        activeRoundId = roundId
        val release = releaseSignal
        var ws: WebSocketSession? = null
        var captureJob: Job? = null
        var streamJob: Job? = null
        var readJob: Job? = null
        try {
            setState(State.Connecting)

            // 1. 先开采集：建连期间的音频进入无界队列，连接完成后作为 pre-roll 一并推流
            val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
            captureJob = scope.launch {
                runCatching { microphone.start(micFormat) }.onFailure {
                    onError?.invoke("录音启动失败：${it.message}")
                    return@launch
                }
                try {
                    microphone.capture().collect { chunk -> audioChannel.send(chunk) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    onError?.invoke("录音采集失败：${e.message}")
                }
            }

            // 2. 连接并发送 full client request
            val session = connect() ?: return
            ws = session
            this.ws = session
            sendMutex.withLock {
                session.send(
                    Frame.Binary(
                        fin = true,
                        data = VolcengineAsrFrameCodec.encodeFullClientRequest(
                            VolcengineAsrProtocol.buildFullClientRequestJson()
                        ),
                    )
                )
            }

            // 3. 推流：队列 → audio only 帧（含 pre-roll 缓冲）
            streamJob = scope.launch {
                for (chunk in audioChannel) {
                    sendMutex.withLock {
                        session.send(
                            Frame.Binary(
                                fin = true,
                                data = VolcengineAsrFrameCodec.encodeAudioOnly(chunk, isLastPacket = false),
                            )
                        )
                    }
                }
            }

            // 服务端响应读取：interim 实时上屏，definite 完成 finalSignal
            val finalSignal = CompletableDeferred<String?>()
            readJob = scope.launch { readLoop(session, roundId, finalSignal) }

            setState(State.Listening)

            // 4. 等待松手
            release.await()

            // 5. 尾音 drain：让 AudioRecord 缓冲尾部音频读完再定稿，避免句尾丢失
            delay(TAIL_DRAIN_MS)
            captureJob?.cancelAndJoin()
            // 排空无界队列中残余的尾音缓冲后再发负包；推流协程的发送由
            // sendMutex 串行化，负包必然排在所有音频帧之后。
            // 等待设上限，推流协程异常退出（如 WS 中断）时避免死循环挂起。
            val drainDeadline = System.currentTimeMillis() + TAIL_DRAIN_MAX_MS
            while (!audioChannel.isEmpty && System.currentTimeMillis() < drainDeadline) {
                delay(TAIL_POLL_MS)
            }

            // 负包定稿
            sendMutex.withLock {
                session.send(
                    Frame.Binary(
                        fin = true,
                        data = VolcengineAsrFrameCodec.encodeAudioOnly(ByteArray(0), isLastPacket = true),
                    )
                )
            }
            setState(State.Finalizing)

            // 6. 等待定稿（6s 超时），取最长累积文本
            val finalText = withTimeoutOrNull(FINAL_TIMEOUT_MS) { finalSignal.await() }
            val result = finalText ?: bestText.takeIf { it.isNotBlank() }
            if (result != null) {
                onFinalResult?.invoke(result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError?.invoke("语音识别失败：${e.message}")
        } finally {
            if (activeRoundId == roundId) {
                activeRoundId = 0
                this.ws = null
                ws?.cancel()
                readJob?.cancel()
                streamJob?.cancel()
                captureJob?.cancelAndJoin()
                runCatching { microphone.close() }
                setState(State.Idle)
            }
        }
    }

    private suspend fun connect(): WebSocketSession? {
        return try {
            client.webSocketSession(urlString = ASR_ENDPOINT) {
                header("X-Api-App-Key", appId)
                header("X-Api-Access-Key", accessKey)
                header("X-Api-Resource-Id", ASR_RESOURCE_ID)
                header("X-Api-Request-Id", UUID.randomUUID().toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError?.invoke("连接识别服务失败：${e.message}")
            null
        }
    }

    /** 读取服务端帧：interim 归并最长文本上屏；definite / 断连 / 错误完成定稿信号。 */
    private suspend fun readLoop(
        session: WebSocketSession,
        roundId: Int,
        finalSignal: CompletableDeferred<String?>,
    ) {
        try {
            for (frame in session.incoming) {
                if (roundId != activeRoundId) return
                when (frame) {
                    is Frame.Binary -> handleServerFrame(frame.data, finalSignal)
                    is Frame.Close -> {
                        finalSignal.complete(bestText.takeIf { it.isNotBlank() })
                        return
                    }
                    else -> Unit
                }
                if (finalSignal.isCompleted) return
            }
            // 对端优雅关闭：以已累积文本收尾
            finalSignal.complete(bestText.takeIf { it.isNotBlank() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError?.invoke("识别连接中断：${e.message}")
        }
    }

    private fun handleServerFrame(data: ByteArray, finalSignal: CompletableDeferred<String?>) {
        val parsed = runCatching { VolcengineAsrFrameCodec.parseServerFrame(data) }.getOrNull()
            ?: return
        when (parsed.messageType) {
            VolcengineAsrFrameCodec.MSG_FULL_SERVER -> {
                val result = parsed.payload.toString(Charsets.UTF_8)
                    .let { VolcengineAsrProtocol.parseServerResult(it) } ?: return
                result.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                    if (text.length > bestText.length) bestText = text
                }
                if (bestText.isNotBlank()) {
                    onInterimResult?.invoke(bestText)
                }
                if (result.definite) {
                    finalSignal.complete(bestText.takeIf { it.isNotBlank() })
                }
            }
            VolcengineAsrFrameCodec.MSG_ERROR -> {
                val message = parsed.errorMessage ?: "错误码 ${parsed.errorCode}"
                onError?.invoke("识别服务错误：$message")
                finalSignal.complete(bestText.takeIf { it.isNotBlank() })
            }
        }
    }

    private fun setState(state: State) {
        onStateChange?.invoke(state)
    }

    private companion object {
        const val ASR_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        const val ASR_RESOURCE_ID = "volc.bigasr.sauc.duration"
        const val TAIL_DRAIN_MS = 400L
        const val TAIL_DRAIN_MAX_MS = 1_000L
        const val TAIL_POLL_MS = 10L
        const val FINAL_TIMEOUT_MS = 6_000L
    }
}
