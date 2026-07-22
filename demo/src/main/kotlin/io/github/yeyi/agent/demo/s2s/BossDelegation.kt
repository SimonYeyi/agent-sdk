package io.github.yeyi.agent.demo.s2s

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.realtime.DelegationResult
import io.github.yeyi.agent.realtime.RealtimeDelegation
import io.github.yeyi.agent.team.BossAgent

class BossDelegation(private val boss: BossAgent) : RealtimeDelegation {
    override suspend fun run(asrText: String): DelegationResult {
        var resultText: String? = null
        var failure: Throwable? = null
        boss.run(asrText).collect { event ->
            when (event) {
                is AgentEvent.Final -> resultText = event.result.message.content
                is AgentEvent.Failed -> failure = event.cause
                else -> Unit
            }
        }
        return resultText?.let { DelegationResult.Success("任务完成, 结果: $it") }
            ?: DelegationResult.Failure(failure?.message ?: "任务未返回结果")
    }
}
