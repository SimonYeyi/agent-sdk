package io.gateway.engine

import io.gateway.api.HookPipeline
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow

internal class DefaultHookPipeline : HookPipeline {

    private val hooks = mutableListOf<HookPipeline.Hook>()
    private val _hookEvents = MutableSharedFlow<Pair<HookPipeline.Event, HookPipeline.Context>>(
        extraBufferCapacity = 100
    )

    val hookEvents: Flow<Pair<HookPipeline.Event, HookPipeline.Context>> = _hookEvents.asSharedFlow()

    @Synchronized
    override fun register(hook: HookPipeline.Hook) {
        hooks.add(hook)
        hooks.sortBy { it.priority }
    }

    @Synchronized
    override fun unregister(hookName: String) {
        hooks.removeAll { it.name == hookName }
    }

    override suspend fun run(event: HookPipeline.Event, context: HookPipeline.Context): HookPipeline.Result {
        var currentContext = context
        var result: HookPipeline.Result = HookPipeline.Result.Continue

        for (hook in hooks) {
            if (event !in hook.events) continue

            when (val hookResult = hook.execute(currentContext)) {
                is HookPipeline.Result.Halt -> {
                    _hookEvents.emit(event to currentContext)
                    return hookResult
                }
                is HookPipeline.Result.ModifyMessage -> {
                    currentContext = currentContext.copy(message = hookResult.newMessage)
                    result = hookResult
                }
                is HookPipeline.Result.ModifyResponse -> {
                    result = hookResult
                }
                is HookPipeline.Result.Continue -> {
                    // 继续下一个
                }
            }
        }

        _hookEvents.emit(event to currentContext)
        return result
    }

    @Synchronized
    override fun getHooks(): List<HookPipeline.Hook> = hooks.toList()

    @Synchronized
    override fun getHooks(event: HookPipeline.Event): List<HookPipeline.Hook> =
        hooks.filter { event in it.events }
}
