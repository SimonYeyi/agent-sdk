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
    private val activeSessionMap = mutableMapOf<String, Session>()

    public suspend fun create(
        accountId: String,
        sessionName: String,
        sessionId: String? = null
    ): Session {
        return mutex.withLock {
            repository.createSession(accountId, sessionName, sessionId)
        }.also {
            pipeline.run(SessionHookEvent.Created(session = it), HookContext())
            start(it)
        }
    }

    /** 查询指定 session，不存在则抛 [NoSuchElementException]。 */
    public suspend fun get(accountId: String, sessionId: String): Session {
        return mutex.withLock {
            repository.findSession(accountId, sessionId)
                ?: throw NoSuchElementException("Session not found: $sessionId")
        }
    }

    /** 查询指定 session，不存在则创建。 */
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

    /** 将 session 标记为活跃（发送 [SessionHookEvent.Start]）。已在活跃状态则忽略。 */
    public suspend fun start(session: Session) {
        val newlyActive = mutex.withLock {
            activeSessionMap.put(session.id, session) == null
        }
        if (!newlyActive) return
        pipeline.run(SessionHookEvent.Start(session), HookContext())
    }

    /** 将 session 标记为非活跃（发送 [SessionHookEvent.Stop]）。已非活跃则忽略。 */
    public suspend fun stop(session: Session) {
        val wasActive = mutex.withLock {
            activeSessionMap.remove(session.id) != null
        }
        if (!wasActive) return
        pipeline.run(SessionHookEvent.Stop(session), HookContext())
    }

    /** 切换到目标 session：先停其他活跃 session，再启动目标 session。 */
    public suspend fun switchTo(session: Session) {
        actives().filter { it.id != session.id }.forEach { stop(it) }
        start(session)
    }

    /** 返回当前所有活跃 session。 */
    public suspend fun actives(): List<Session> = mutex.withLock {
        activeSessionMap.values.toList()
    }

    /** 删除指定 session。发送 [SessionHookEvent.Deleted]。 */
    public suspend fun delete(session: Session) {
        stop(session)
        mutex.withLock {
            repository.deleteSession(session.accountId, session.id)
        }?.let {
            pipeline.run(SessionHookEvent.Deleted(session), HookContext())
        }
    }

    /** 列出账号下所有 session（不限活跃状态）。 */
    public suspend fun list(accountId: String): List<Session> {
        return mutex.withLock {
            repository.findSessions(accountId)
        }
    }
}
