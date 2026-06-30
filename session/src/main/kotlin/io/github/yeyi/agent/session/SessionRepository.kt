package io.github.yeyi.agent.session

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

public class SessionRepository(baseDir: File) {

    private val sessionsDir = File(baseDir, "agent/sessions")

    private val json = Json { ignoreUnknownKeys = true }

    private fun getUserDir(accountId: String): File {
        return File(sessionsDir, accountId).also { it.mkdirs() }
    }

    private fun getSessionsFile(accountId: String): File {
        return File(getUserDir(accountId), "sessions.jsonl")
    }

    private fun getMemoryFile(accountId: String, sessionId: String): File {
        return File(File(getUserDir(accountId), "memories"), "$sessionId.jsonl").also {
            it.parentFile.mkdirs()
        }
    }

    private fun getConversationDir(accountId: String, sessionId: String): File {
        return File(File(getUserDir(accountId), "conversations"), sessionId).also {
            it.mkdirs()
        }
    }

    private fun readSessionsFromFile(accountId: String): List<Session> {
        val file = getSessionsFile(accountId)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<Session>(it) }
    }

    public fun createSession(accountId: String, sessionName: String, sessionId: String?): Session {
        val now = Clock.System.now()
        val id = sessionId ?: UUID.randomUUID().toString()
        require(findSession(accountId, id) == null) { "Session already exists: $id" }
        val session = Session(
            id = id,
            accountId = accountId,
            name = sessionName,
            createdAt = now,
            lastActiveAt = now
        ).let { hydrateSession(it) }
        saveSession(session)
        return session
    }

    private fun hydrateSession(session: Session): Session {
        val rawMemory = JsonlBackedMemory(getMemoryFile(session.accountId, session.id))
        val conversation =
            JsonlConversation(getConversationDir(session.accountId, session.id), rawMemory)
        return session.copy(
            _memory = conversation,
            _conversation = conversation
        )
    }

    public fun findSessions(accountId: String): List<Session> {
        return readSessionsFromFile(accountId).map { hydrateSession(it) }
    }

    public fun findSession(accountId: String, sessionId: String): Session? {
        return findSessions(accountId).find { it.id == sessionId }
    }

    private fun saveSession(session: Session) {
        val file = getSessionsFile(session.accountId)
        val sessions = readSessionsFromFile(session.accountId).toMutableList()
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) {
            sessions[index] = session
        } else {
            sessions.add(session)
        }
        file.writeText(sessions.joinToString("\n") { json.encodeToString(it) })
    }

    public fun deleteSession(accountId: String, sessionId: String): Session? {
        val sessions = readSessionsFromFile(accountId)
        val toDelete = sessions.firstOrNull { it.id == sessionId } ?: return null
        val remaining = sessions.filterNot { it.id == sessionId }

        val sessionsFile = getSessionsFile(accountId)
        sessionsFile.writeText(remaining.joinToString("\n") { json.encodeToString(it) })

        val memoryFile = getMemoryFile(accountId, sessionId)
        if (memoryFile.exists()) {
            memoryFile.delete()
        }

        val conversationDir = getConversationDir(accountId, sessionId)
        if (conversationDir.exists()) {
            conversationDir.deleteRecursively()
        }

        return hydrateSession(toDelete)
    }
}