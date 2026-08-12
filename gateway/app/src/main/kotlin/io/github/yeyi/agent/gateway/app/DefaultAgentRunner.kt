package io.github.yeyi.agent.gateway.app

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentQuery
import io.gateway.api.AgentRunner
import io.gateway.model.GatewaySession
import io.gateway.model.IncomingMessage
import io.gateway.model.MessageContent
import io.github.yeyi.agent.awaitResult
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

        val agent = createAgent(accountId, sessionId, sessionName)
        val agentResult = agent.run(AgentQuery.text(message.content.toString())).awaitResult()
        return AgentRunner.Result.Success(MessageContent.Text(agentResult.message.content!!))
    }

    override fun observeStream(sessionKey: String): Flow<String>? = null
}
