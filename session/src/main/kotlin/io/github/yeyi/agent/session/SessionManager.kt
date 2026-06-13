package io.github.yeyi.agent.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

public class SessionManager(
    sessionParent: File,
    private val hook: SessionHook = NoOpAgentHook
) {

    private val repository = SessionRepository(sessionParent)
    private val mutex = Mutex()

    public suspend fun create(userId: String, sessionName: String): Session {
        return mutex.withLock {
            repository.createSession(userId, sessionName).also {
                hook.safeInvoke { hook.onSessionCreated(it) }
            }
        }
    }

    public suspend fun get(userId: String, sessionId: String): Session {
        return mutex.withLock {
            repository.findSession(userId, sessionId)
                ?: throw NoSuchElementException("Session not found: $sessionId")
        }
    }

    public suspend fun delete(userId: String, sessionId: String) {
        mutex.withLock {
            repository.deleteSession(userId, sessionId)
            hook.safeInvoke { hook.onSessionDeleted(userId, sessionId) }
        }
    }

    public suspend fun list(userId: String): List<Session> {
        return mutex.withLock {
            repository.findSessions(userId)
        }
    }
}