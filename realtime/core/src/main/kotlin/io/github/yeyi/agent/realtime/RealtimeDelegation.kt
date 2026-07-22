package io.github.yeyi.agent.realtime

interface RealtimeDelegation {
    suspend fun run(asrText: String): DelegationResult
}

sealed interface DelegationResult {
    data class Success(val text: String) : DelegationResult
    data class Failure(val message: String) : DelegationResult
}
