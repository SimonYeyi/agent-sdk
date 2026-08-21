package io.github.yeyi.agent.session

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

internal class SessionRepository(baseDir: File) {

    private val sessionsDir = File(baseDir, "agent/sessions")

    private val json = Json { ignoreUnknownKeys = true }

    private fun sanitizeForPath(s: String): String =
        s.replace(Regex("""[<>:"/\\|?*]"""), "-")

    private fun getAccountDir(accountId: String): File {
        return File(sessionsDir, sanitizeForPath(accountId)).also { it.mkdirs() }
    }

    private fun getSessionsFile(accountId: String): File {
        return File(getAccountDir(accountId), "sessions.jsonl")
    }

    /**
     * 每个 session 一个独立目录,位于 `sessions/{accountId}/{sessionId}/` 下,
     * 内部三块同级:
     * - `memory.jsonl` —— [JsonlBackedMemory] 持久化 (Memory 接口)
     * - `conversations/page*.jsonl` —— [JsonlConversation] 分页存储 (Conversation 接口)
     * - `media/{uuid}` —— [FilesystemMediaArchive] 字节存档
     *
     * deleteSession 时整 `getSessionDir()` 目录 deleteRecursively 一并清理。
     */
    private fun getSessionDir(accountId: String, sessionId: String): File =
        File(getAccountDir(accountId), sanitizeForPath(sessionId))
            .also { it.mkdirs() }

    private fun getMemoryFile(accountId: String, sessionId: String): File =
        File(getSessionDir(accountId, sessionId), "memory.jsonl")

    private fun getConversationDir(accountId: String, sessionId: String): File =
        File(getSessionDir(accountId, sessionId), "conversations")

    private fun getMediaDir(accountId: String, sessionId: String): File =
        File(getSessionDir(accountId, sessionId), "media")

    private fun readSessionsFromFile(accountId: String): List<Session> {
        val file = getSessionsFile(accountId)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<Session>(it) }
    }

    /**
     * 创建新 session。
     *
     * @param accountId 账号标识
     * @param sessionName session 名称
     * @param sessionId 可选指定 ID，不传则自动生成 UUID
     * @throws IllegalArgumentException sessionId 已存在
     */
    fun createSession(accountId: String, sessionName: String, sessionId: String?): Session {
        val now = Clock.System.now()
        val id = sessionId ?: UUID.randomUUID().toString()
        require(findSession(accountId, id) == null) { "Session already exists: $id" }
        val session = Session(
            id = id,
            accountId = accountId,
            name = sessionName.take(50),
            createdAt = now,
            lastActiveAt = now
        ).let { hydrateSession(it) }
        saveSession(session)
        return session
    }

    /**
     * 构造链:FilesystemMediaArchive → JsonlBackedMemory → JsonlConversation。
     */
    private fun hydrateSession(session: Session): Session {
        val archive = FilesystemMediaArchive(
            getMediaDir(session.accountId, session.id),
        )
        val rawMemory = JsonlBackedMemory(
            getMemoryFile(session.accountId, session.id),
            archive,  // 注入到最下层,所有上层通过 Memory by 自动转发
        )
        val conversation = JsonlConversation(
            getConversationDir(session.accountId, session.id),
            rawMemory,
        )
        return session.copy(
            _memory = conversation,
            _conversation = conversation,
        )
    }

    /** 列出账号下所有 session。 */
    fun findSessions(accountId: String): List<Session> {
        return readSessionsFromFile(accountId).map { hydrateSession(it) }
    }

    /** 按 ID 查找 session，找不到返回 null。 */
    fun findSession(accountId: String, sessionId: String): Session? {
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

    /**
     * 删除 session 下的所有内容 (`memory.jsonl` + `conversations/` + `media/`),
     * 索引条目同步从 `sessions.jsonl` 移除。整 session 目录在 [getSessionDir]
     * 下,一行 deleteRecursively 覆盖三块。
     *
     * @return 被删除的 session(删除前状态),找不到返回 null
     */
    fun deleteSession(accountId: String, sessionId: String): Session? {
        val sessions = readSessionsFromFile(accountId)
        val toDelete = sessions.firstOrNull { it.id == sessionId } ?: return null
        val remaining = sessions.filterNot { it.id == sessionId }

        val sessionsFile = getSessionsFile(accountId)
        sessionsFile.writeText(remaining.joinToString("\n") { json.encodeToString(it) })

        val sessionDir = getSessionDir(accountId, sessionId)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }

        // 返回删除前的快照(transient memory/conversation 仍为 null —— session 已删,
        // 不重新 hydrate 避免 mkdirs() 复活 session 目录)
        return toDelete
    }
}
