package io.github.yeyi.agent.memory.shared

import java.time.Instant

/**
 * 一个共享记忆 —— 纯值对象:元数据 + 对 `.md` 文件全文(frontmatter + 正文)的解析/渲染能力。
 *
 * 不持有 [SharedMemoryStore],没有任何 IO:本类只回答"这段文本是什么记忆"和
 * "把记忆渲染回文本",读写编排由 [SharedMemoryLoader] 负责。
 *
 * 由 [SharedMemoryLoader] 在 reload 时解析构建,不依赖外部宿主注册。
 *
 * **internal**:纯粹是模块内实现细节,宿主不感知本类型(公开面只有一个 DSL 函数)。
 *
 * - "按需加载":description 告诉 LLM 何时使用,正文不预加载
 * - 正文不在实体上驻留,而是在 [parse] / [bodyOf] / [render] 之间以 String 流转
 * - 淘汰判断只由 `SharedMemoryLoader.reload` 做(条目超出 `maxFileCount` 时按 [lastAccessedAt] 由远到近)
 */
internal class SharedMemory(
    /** 记忆名(等于文件名,不含 .md)。解析时以文件名为准。 */
    val name: String,
    /** 给 LLM 看的"何时使用本记忆"提示。 */
    val description: String,
    /** 创建时间。 */
    val createdAt: Instant,
    /** 最后一次被 load/save 的时间。只用作条数淘汰时的排序依据(最久未访问的先删)。 */
    val lastAccessedAt: Instant,
) {
    /** 拷贝一份新实体,并把访问时间更新为 [lastAccessedAt](实体不可变)。 */
    fun copyWith(lastAccessedAt: Instant): SharedMemory =
        SharedMemory(name, description, createdAt, lastAccessedAt)

    /**
     * 用本元数据 + [body] 渲染 md 全文,写回文件时使用。
     *
     * 记忆文件只由本模块读写,**不是给人编辑的入口**:每次读写都按下列格式整体重写,
     * 手工加入的字段 / 注释 / 缩进 / 换行风格都会在下次读写时被规范化(甚至被丢弃)。
     *
     * frontmatter 固定 4 行,值永远单行:`description` 渲染前把换行折成空格
     * (description 是给 LLM 看的一句话,不需要多行);时间用 `Instant.toString()`(ISO-8601)。
     * 不做 YAML 引号转义——解析端按"第一个 `:` 切分 + trim"读回,两边规则必须对称。
     */
    fun render(body: String): String = buildString {
        append("---\n")
        append("name: ").append(name).append('\n')
        append("description: ").append(description.replace('\n', ' ')).append('\n')
        append("createdAt: ").append(createdAt).append('\n')
        append("lastAccessedAt: ").append(lastAccessedAt).append('\n')
        append("---\n")
        append(body)
    }

    companion object {
        /** 保留名:跨会话用户画像。不参与记忆清单,也不允许 LLM 直接 load。 */
        const val RESERVED_USER: String = "user"

        /**
         * 纯函数:解析 md 全文的 frontmatter 构建元数据。[name] 取文件名(不含 .md),
         * 与 frontmatter 中的 name 冲突时以文件名为准。不抛异常。
         *
         * 缺字段兜底原则是"宁可当新,不要排到淘汰队首":
         * - 缺 `createdAt` → 用 `lastAccessedAt`;缺 `lastAccessedAt` → 用 `createdAt`
         * - 两者都缺或时间戳无法解析 → 用解析时刻 now(而不是 EPOCH,否则它永远是条数淘汰的第一个)
         * - 缺 `description` → 空串
         * - 没有 frontmatter(整段都是正文)→ 全部兜底,全文作正文
         */
        fun parse(name: String, content: String): SharedMemory {
            val lines = content.lines()

            // 没有 frontmatter(或首行不是 ---):整段都是正文,全部兜底
            if (lines.firstOrNull()?.trim() != "---") return create(name, "", Instant.now())

            // 找结束分隔线;未闭合同样按"没有 frontmatter"处理(宁可当新,不要误解析半截)
            val closing = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (closing < 0) return create(name, "", Instant.now())

            val fields = HashMap<String, String>()
            for (line in lines.subList(1, closing + 1)) {
                val sep = line.indexOf(':')
                if (sep <= 0) continue                       // 空行 / 没有 key 的行跳过
                fields[line.substring(0, sep).trim()] = line.substring(sep + 1).trim()
            }

            val now = Instant.now()
            fun time(key: String): Instant? =
                fields[key]?.let { runCatching { Instant.parse(it) }.getOrNull() }

            val created = time("createdAt") ?: time("lastAccessedAt") ?: now
            val accessed = time("lastAccessedAt") ?: created

            return SharedMemory(
                name = name,                                 // 文件名优先,忽略 frontmatter 里的 name
                description = fields["description"].orEmpty(),
                createdAt = created,
                lastAccessedAt = accessed,
            )
        }

        /** 取正文(去掉 frontmatter 后的部分);没有 frontmatter 时返回全文。 */
        fun bodyOf(content: String): String {
            val lines = content.lines()
            if (lines.firstOrNull()?.trim() != "---") return content
            val closing = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (closing < 0) return content
            // closing 是 lines.drop(1) 的下标,对应原 lines 的 closing + 1;正文从它再往后一行起
            return lines.drop(closing + 2).joinToString("\n")
        }

        /** 新建一个记忆(user.md 初始化用)。 */
        fun create(name: String, description: String, now: Instant): SharedMemory =
            SharedMemory(name, description, createdAt = now, lastAccessedAt = now)
    }
}
