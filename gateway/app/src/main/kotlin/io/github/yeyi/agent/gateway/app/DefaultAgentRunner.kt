package io.github.yeyi.agent.gateway.app

import io.github.yeyi.agent.Agent
import io.gateway.api.AgentRunner
import io.gateway.model.GatewaySession
import io.gateway.model.IncomingMessage
import io.gateway.model.MessageContent
import io.github.yeyi.agent.awaitResult
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.flow.Flow

class DefaultAgentRunner(
    private val createAgent: suspend (accountId: String, sessionId: String, sessionName: String) -> Agent,
) : AgentRunner {

    override suspend fun process(
        message: IncomingMessage,
        session: GatewaySession
    ): AgentRunner.Result {
        val accountId = "gateway:${session.platform.value}"
        val sessionId = "${session.chatId}:${session.userId}"
        val sessionName = (message.content as? MessageContent.Text)?.text ?: sessionId

        return try {
            val agent = createAgent(accountId, sessionId, sessionName)
            val agentResult = agent.run(message.content.toString()).awaitResult()
            AgentRunner.Result.Success(MessageContent.Text(agentResult.message.content!!))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentRunner.Result.Failure(error = e.message ?: "Unknown error", exception = e)
        }
    }

    override fun observeStream(sessionKey: String): Flow<String>? = null
}
