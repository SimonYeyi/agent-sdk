package io.github.yeyi.agent.gateway.app

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.gateway.api.AgentRunner
import io.gateway.model.GatewaySession
import io.gateway.model.IncomingMessage
import io.gateway.model.MessageContent.Resource
import io.gateway.model.MessageContent
import io.github.yeyi.agent.awaitResult
import kotlinx.coroutines.flow.Flow
import java.util.Base64

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
        val query = toQuery(message.content)
        val agentResult = agent.run(query).awaitResult()
        return AgentRunner.Result.Success(MessageContent.Text(agentResult.message.content!!))
    }

    override fun observeStream(sessionKey: String): Flow<String>? = null

    private fun resourceToPart(resource: Resource): ContentPart? = when (resource) {
        is Resource.Bytes -> when {
            resource.mime.startsWith("image/") -> ContentPart.Image(
                MediaSource.Data(resource.mime, Base64.getEncoder().encodeToString(resource.data))
            )
            resource.mime.startsWith("audio/") -> ContentPart.Audio(
                MediaSource.Data(resource.mime, Base64.getEncoder().encodeToString(resource.data))
            )
            resource.mime.startsWith("video/") -> ContentPart.Video(
                MediaSource.Data(resource.mime, Base64.getEncoder().encodeToString(resource.data))
            )
            else -> null
        }
        is Resource.Http -> null
    }

    private fun toQuery(c: MessageContent): AgentQuery = when (c) {
        is MessageContent.Text -> AgentQuery.text(c.text)
        is MessageContent.Image -> {
            val parts = c.parts.mapNotNull(::resourceToPart)
            if (parts.isNotEmpty()) AgentQuery(parts) else AgentQuery.text(c.toString())
        }
        is MessageContent.Audio -> singleResourceQuery(c.resource, c.toString())
        is MessageContent.Video -> singleResourceQuery(c.resource, c.toString())
        is MessageContent.Document -> singleResourceQuery(c.resource, c.toString())
        is MessageContent.Mixed -> {
            val parts = c.parts.flatMap { part -> toQuery(part).parts }
            if (parts.isNotEmpty()) AgentQuery(parts) else AgentQuery.text(c.toString())
        }
        else -> AgentQuery.text(c.toString())
    }

    private fun singleResourceQuery(resource: Resource, fallback: String): AgentQuery {
        val part = resourceToPart(resource)
        return if (part != null) AgentQuery(listOf(part)) else AgentQuery.text(fallback)
    }
}
