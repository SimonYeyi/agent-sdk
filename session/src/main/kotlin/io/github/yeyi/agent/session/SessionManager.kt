package io.github.yeyi.agent.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

public class SessionManager(baseDir: File, private val hook: SessionHook = NoOpAgentHook) {
    private val repository = SessionRepository(baseDir)
    private val mutex = Mutex()

    public suspend fun create(accountId: String, sessionName: String, sessionId: String? = null): Session {
        return mutex.withLock {
            repository.createSession(accountId, sessionName, sessionId).also {
                hook.safeInvoke { hook.onSessionCreated(it) }
            }
        }
    }

    public suspend fun get(accountId: String, sessionId: String): Session {
        return mutex.withLock {
            repository.findSession(accountId, sessionId)
                ?: throw NoSuchElementException("Session not found: $sessionId")
        }
    }

    public suspend fun delete(accountId: String, sessionId: String) {
        mutex.withLock {
            repository.deleteSession(accountId, sessionId)
            hook.safeInvoke { hook.onSessionDeleted(accountId, sessionId) }
        }
    }

    public suspend fun list(accountId: String): List<Session> {
        return mutex.withLock {
            repository.findSessions(accountId)
        }
    }
}