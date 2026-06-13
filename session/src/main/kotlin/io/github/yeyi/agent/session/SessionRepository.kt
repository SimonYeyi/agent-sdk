package io.github.yeyi.agent.session

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

public class SessionRepository(sessionParent: File) {

    private val baseDir = File(sessionParent, "sessions")

    private val json = Json { ignoreUnknownKeys = true }

    private fun getUserDir(userId: String): File {
        return File(baseDir, userId).also { it.mkdirs() }
    }

    private fun getSessionsFile(userId: String): File {
        return File(getUserDir(userId), "sessions.jsonl")
    }

    private fun getMemoryFile(userId: String, sessionId: String): File {
        return File(File(getUserDir(userId), "memories"), "$sessionId.jsonl").also {
            it.parentFile.mkdirs()
        }
    }

    private fun readSessionsFromFile(userId: String): List<Session> {
        val file = getSessionsFile(userId)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<Session>(it) }
    }

    public fun createSession(userId: String, sessionName: String): Session {
        val now = Clock.System.now()
        val id = UUID.randomUUID().toString()
        val memoryFile = getMemoryFile(userId, id)
        val session = Session(
            id = id,
            userId = userId,
            name = sessionName,
            createdAt = now,
            lastActiveAt = now,
            _memory = JsonlBackedMemory(memoryFile)
        )
        saveSession(session)
        return session
    }

    public fun findSessions(userId: String): List<Session> {
        return readSessionsFromFile(userId).map { session ->
            session.copy(_memory = JsonlBackedMemory(getMemoryFile(userId, session.id)))
        }
    }

    public fun findSession(userId: String, sessionId: String): Session? {
        return findSessions(userId).find { it.id == sessionId }
    }

    public fun saveSession(session: Session) {
        val file = getSessionsFile(session.userId)
        val sessions = readSessionsFromFile(session.userId).toMutableList()
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) {
            sessions[index] = session
        } else {
            sessions.add(session)
        }
        file.writeText(sessions.joinToString("\n") { json.encodeToString(it) })
    }

    public fun deleteSession(userId: String, sessionId: String) {
        val filtered = readSessionsFromFile(userId).filter { it.id != sessionId }

        val sessionsFile = getSessionsFile(userId)
        sessionsFile.writeText(filtered.joinToString("\n") { json.encodeToString(it) })

        val memoryFile = getMemoryFile(userId, sessionId)
        if (memoryFile.exists()) {
            memoryFile.delete()
        }
    }
}