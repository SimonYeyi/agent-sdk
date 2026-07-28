package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.RealtimeAppliance
import io.github.yeyi.agent.realtime.RealtimeDelegation
import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.SessionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Volc Realtime Appliance using the Volc SpeechEngine SDK.
 *
 * NOTE: This implementation requires the speechengine_tob SDK to be available.
 * The SDK artifact must be manually installed or the Volc private Maven repository
 * must be configured in your Gradle settings.
 *
 * See: realtime/providers/volc_android_sdk.md for SDK setup instructions.
 */
public class VolcRealtimeAppliance(
    private val sessionConfig: SessionConfig,
    override val delegation: RealtimeDelegation? = null,
) : RealtimeAppliance {

    private val eventEmitter = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)

    // TODO: When speechengine_tob SDK is available, uncomment the following:
    // private val protocolAdapter = VolcRealtimeAdapter()
    // private var engine: SpeechEngine? = null
    // private val delegationHandler: DelegationHandler? = delegation?.let { ... }

    override val events: Flow<RealtimeEvent> = eventEmitter.asSharedFlow()

    override suspend fun start() {
        // TODO: Implement using SpeechEngine SDK
        // See realtime/providers/volc_android_sdk.md for SDK integration details
        error(
            "VolcRealtimeAppliance requires speechengine_tob SDK. " +
                "See realtime/providers/volc_android_sdk.md for setup instructions."
        )
    }

    override suspend fun close() {
        // TODO: Implement using SpeechEngine SDK
        error(
            "VolcRealtimeAppliance requires speechengine_tob SDK. " +
                "See realtime/providers/volc_android_sdk.md for setup instructions."
        )
    }
}

// TODO: When speechengine_tob SDK is available, uncomment these imports and implementation:
// import com.bytedance.speech.speechengine.SpeechEngine
// import com.bytedance.speech.speechengine.SpeechEngineDefines
// import com.bytedance.speechengine.SpeechEngineGenerator
// import io.github.yeyi.agent.realtime.DelegationHandler
// import kotlinx.coroutines.CoroutineScope
// import kotlinx.coroutines.Dispatchers
// import kotlinx.coroutines.SupervisorJob
// import kotlinx.coroutines.launch
//
// private const val DIALOG_ENGINE = "DialogDuplex"
// private const val DIALOG_DUPLEX_DEFAULT_RESOURCE_ID = "agent-sdk"
// private const val UID = "agent-sdk"
