package io.github.yeyi.agent.session

import io.github.yeyi.agent.hook.HookContext
import io.github.yeyi.agent.hook.HookPipeline
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Session 管理器，直接使用 [HookPipeline] 发送生命周期事件。
 */
public class SessionManager(
    baseDir: File,
    private val pipeline: HookPipeline
) {
    private val repository = SessionRepository(baseDir)
    private val mutex = Mutex()

    public suspend fun create(
        accountId: String,
        sessionName: String,
        sessionId: String? = null
    ): Session {
        return mutex.withLock {
            repository.createSession(accountId, sessionName, sessionId).also {
                pipeline.run(SessionHookEvent.Created(session = it), HookContext())
            }
        }
    }

    public suspend fun get(accountId: String, sessionId: String): Session {
        return mutex.withLock {
            repository.findSession(accountId, sessionId)
                ?: throw NoSuchElementException("Session not found: $sessionId")
        }
    }

    public suspend fun getOrCreate(
        accountId: String,
        sessionName: String,
        sessionId: String
    ): Session = try {
        get(accountId, sessionId)
    } catch (_: NoSuchElementException) {
        create(
            accountId = accountId,
            sessionName = sessionName,
            sessionId = sessionId,
        )
    }

    public suspend fun delete(accountId: String, sessionId: String) {
        mutex.withLock {
            repository.deleteSession(accountId, sessionId)
            pipeline.run(SessionHookEvent.Deleted(accountId = accountId, sessionId = sessionId), HookContext())
        }
    }

    public suspend fun list(accountId: String): List<Session> {
        return mutex.withLock {
            repository.findSessions(accountId)
        }
    }
}
