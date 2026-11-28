package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * 压缩输入的容错倍数:`newBody` 超过 `maxFileCharSize` 的这个倍数时直接拒绝写入。
 *
 * compressMemory 要把全文塞进 LLM 的 prompt,连输入窗口都装不下的内容压不了;
 * 放行只会让磁盘上多一个永远超长的记忆,之后每次 load 都击穿上下文预算。
 */
private const val MAX_COMPRESS_INPUT_FACTOR = 4

/**
 * LLM 写入共享记忆的工具。
 *
 * 参数:
 * - name: 记忆名(对应文件名,不含 .md)。字符合集 `[A-Za-z0-9._-]`、≤ 64,与 store 的校验一致
 *   (不校验就会变成路径穿越:`../../x` 写出目录之外)
 * - description: 记忆描述(给 LLM 看的"何时使用本记忆"提示)。**新建记忆时必填**;已有记忆忽略此参数
 * - content: 写入内容。正文一行一条,所以这里也应是单行
 * - mode: "append" 在正文末尾新增一条条目 / "update" 用 content 整条替换掉一条旧条目。
 *   正文按行组织(一行一条),所以 old_text 必须与其中一整行完全相同,update 天然只命中一条;
 *   普通记忆改内容必须先 load 拿到正文再复制 old_text;用户画像 user 的正文常驻 system prompt,
 *   所以 update 天然是它的主路径。
 *
 * 副作用:
 * - 更新 frontmatter.lastAccessedAt(= now):写入即访问
 * - 写入后 loader.modify 内部自动 reload:重建快照(新记忆由此进入清单);若记忆条数超过 maxFileCount,
 *   按 lastAccessedAt 由远到近淘汰,直到回到条数内(本次写入的记忆刚被 touch,排在队尾)
 * - 写入后若 newBody 超过 maxFileCharSize(字符数,≈ token),先在 transform 内同步调 LLM 压缩,
 *   算完 finalBody 再一次性写入(与 write 同一把 per-name 锁,无竞态窗口)
 * - 写入的是 user 时,写成功后调 [onUserProfileUpdated] 把**最终正文**(可能已被压缩)原位替换进
 *   system prompt 的 user 段落。下一次 buildRequest 就看到新值——user 永远不会停在 install 快照上。
 *
 * **不判淘汰**:写入即视为访问。单条超出 maxFileCharSize 时先试压缩,但压缩可能失败——
 * 所以它是**软上限**(失败时原文照存并回执 WARNING);真正拦死超长入参的是"超过上限 4 倍直接拒绝写入"。
 * 条数超额的淘汰只在随后的 reload 里发生,且针对的是**其他**最久未访问的记忆。
 *
 * 返回:写入确认信息。不回字符数(内部指标,模型无可行动的判断依据);
 * 但压缩成功/失败必须说明——磁盘上的内容可能不等于它写入的原文。
 *
 * 并发语义:本工具自身不持锁,串行性由 [SharedMemoryStore] 的 per-name 互斥保证——
 * save 与 load、save 与压缩对同一 name 自动串行。存储经 [SharedMemoryLoader.modify] 访问。
 */
internal class SharedMemorySaveTool(
    private val loader: SharedMemoryLoader,
    private val maxFileCharSize: Int,
    private val llmProvider: LlmProvider?,
    /** user 正文落盘后的回调(由插件注入,内部调 addInstruction 原位替换 persona 段落);测试可传 null。 */
    private val onUserProfileUpdated: ((String) -> Unit)? = null,
) : Tool {
    override val name: String = "save_shared_memory"

    override val description: String = """
        将信息写入共享记忆文件。当用户要求或你发现了值得跨会话长期记住的事实(偏好、背景、约定等)时调用本工具。

        调用前请先看 load_shared_memory 工具描述里列出的（已存在）记忆文件列表,选择要记录的内容归属于哪个记忆文件:
        - 有合适的记忆文件:传该记忆文件的 name,description 留空(记忆文件已存在，不需要描述)
        - 没有合适的记忆文件:传新 name + 必填的 description(一句话告诉未来的自己何时该加载本记忆文件)，由程序新建

        另有一个内置记忆 "user"(用户画像),它不出现在上面的列表里,内容每轮自动注入你的 system prompt:
        - 用户透露了稳定的偏好、背景、习惯、身份信息时,用 name="user" 写入,它会跨会话注入；
        - system prompt 里那段关于用户偏好/背景的文字就是 user 的现有内容,update 时引用其中原文作 old_text。

        参数:
        - name: 记忆文件名(英文短词,如 "travel-preferences")。
        - description: 记忆文件描述。新建记忆文件时必填,选中合适的记忆文件时忽略。例:"用户旅行偏好与历史行程"
        - content: 记忆内容。正文按行组织,一行一条,所以 content 也应是单行;
                   append 模式下必填,作为新条目追加到正文末尾;
                   update 模式下作为该条目的新版本(空串表示删除该条目)。
        - mode: "append" 或 "update",默认 "append"。
          - append: 在正文末尾新增一条条目。记忆文件新建时也走此模式(此时正文为空)。
          - update: 整条更新。old_text 必须与现有正文中的**一整行完全相同**(不只是其中几个字),
                    工具用 content 替换掉那一行。这是条目替换,不是词句替换;
                    要改多条就调多次,每次给一条。
        - old_text: 仅 update 模式必填。要被替换的那一整条条目,必须与正文中某一行完全一致
                    (含你写入时的标点与空格),不能为空串。不是关键词,也不是半句话。
    """.trimIndent()

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"name":{"type":"string"},"description":{"type":"string"},"content":{"type":"string"},"mode":{"type":"string","enum":["append","update"]},"old_text":{"type":"string"}},"required":["name"]}"""
    )

    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult {
        val name = arguments.jsonObject["name"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult.error("Missing 'name'")
        if (!MEMORY_NAME_PATTERN.matches(name)) {
            return ToolExecutionResult.error("Invalid 'name': use [A-Za-z0-9._-] only, max 64 chars")
        }
        val description = arguments.jsonObject["description"]?.jsonPrimitive?.content
        val mode = arguments.jsonObject["mode"]?.jsonPrimitive?.content ?: "append"
        val content = arguments.jsonObject["content"]?.jsonPrimitive?.content
            ?: if (mode == "append") return ToolExecutionResult.error("Missing 'content'") else ""
        val oldText = arguments.jsonObject["old_text"]?.jsonPrimitive?.content
        // old_text 必须指向一段真实文本:空串能通过 contains 校验,而按它做替换会在
        // 每两个字符之间插入 content,把整篇正文毁掉
        if (mode == "update" && oldText.isNullOrBlank()) {
            return ToolExecutionResult.error("Missing or empty 'old_text': quote an existing passage verbatim")
        }

        val provider = llmProvider ?: context.agentContext.llmProvider
        val now = Instant.now()

        // 状态变量:由 transform 内部写入,execute 外层读取以决定返回值
        var notFound = false            // update 模式 old_text 找不到 / update 不存在 / 非法 mode
        var missingDescription = false  // 新建记忆但 description 为空
        var tooLarge = false            // 远超 maxFileCharSize,连压缩输入都装不下 → 拒绝写入
        var compressed = false          // 超限且压缩成功(正文已被改写)
        var overLimitKept = false       // 超限但压缩失败(超长原文照存)
        var storedBody = ""             // 最终写入的正文(user 用它刷新 system prompt)

        loader.modify(name) { current ->
            val meta = if (current == null) {
                if (description.isNullOrBlank()) { missingDescription = true; return@modify current }
                SharedMemory.create(name, description, now)
            } else {
                // 已有记忆:description / createdAt 沿用旧值,忽略入参
                SharedMemory.parse(name, current)
            }
            val body = current?.let { SharedMemory.bodyOf(it) } ?: ""

            // 失败路径一律 return@modify current:store 把 null 视为"删除文件",
            // 只有原样返回才不会被误当成删除请求
            val newBody = when (mode) {
                // 一行一条:追加就是接一行
                "append" -> if (body.isBlank()) content else "$body\n$content"
                "update" -> {
                    // 条目级替换:old_text 必须与某一整行完全一致(trim 后比较)。
                    // 不按子串匹配——否则只引用几个字也会命中,结果改掉半句话
                    val target = oldText!!.trim()
                    val lines = body.lines()
                    val index = lines.indexOfFirst { it.trim() == target }
                    if (current == null || index < 0) { notFound = true; return@modify current }
                    // content 为空串 → 删除该条目(removeAt,不留空行);否则整行换成新版本
                    lines.toMutableList().apply {
                        if (content.isBlank()) removeAt(index) else this[index] = content
                    }.joinToString("\n")
                }
                else -> { notFound = true; return@modify current }
            }

            // 压缩本身要把 newBody 整个塞进 LLM prompt,连输入窗口都装不下的内容压不了:
            // 放行只会落一个永远超长的文件、之后每次 load 都爆上下文预算
            if (newBody.length > maxFileCharSize * MAX_COMPRESS_INPUT_FACTOR) {
                tooLarge = true
                return@modify current
            }

            // compressMemory 返回非 null 时已保证比原文短,无需再比长度
            val finalBody = if (newBody.length > maxFileCharSize) {
                compressMemory(provider, maxFileCharSize, newBody)?.also {
                    compressed = true
                } ?: newBody.also { overLimitKept = true }
            } else {
                newBody
            }
            storedBody = finalBody          // 供 user 刷新 persona 段落用
            meta.copyWith(lastAccessedAt = now).render(finalBody)   // 写入即访问
        }

        when {
            tooLarge -> return ToolExecutionResult.error(
                "Content is over ${maxFileCharSize * MAX_COMPRESS_INPUT_FACTOR} chars, too large to compress; " +
                    "split it into several saves of under $maxFileCharSize chars each"
            )
            missingDescription -> return ToolExecutionResult.error(
                "Missing 'description': required when creating a new memory '$name'"
            )
            notFound -> return ToolExecutionResult.error(
                if (mode == "update") {
                    "old_text not found: no line in '$name' matches it exactly; quote the whole entry line"
                } else "Invalid mode: $mode"
            )
        }

        // user 的正文就是 system prompt 的一部分:写成功后立即把最终正文(可能已被压缩)
        // 原位替换进 persona。这是 user "永远最新" 的唯一机制:不需要 turn hook,也不需要重读磁盘。
        // 压缩会改写正文,所以这里必须用落盘后的 storedBody,而不是调用方传入的 content。
        if (name == SharedMemory.RESERVED_USER) {
            onUserProfileUpdated?.invoke(storedBody)
        }

        // 回执只给模型能据此行动的信息:字符数不回;压缩/超限必须回,
        // 否则模型会以为磁盘上还是它写下的原文,后续 update 的 old_text 就对不上
        val note = when {
            compressed -> " Content was compressed to fit maxFileCharSize; the stored text is a rewritten summary, not what you sent."
            overLimitKept -> " WARNING: content exceeds maxFileCharSize and compression failed; it was stored as-is."
            else -> ""
        }
        return ToolExecutionResult.success("Saved memory '$name'.$note")
    }

    private suspend fun compressMemory(
        llmProvider: LlmProvider,
        maxFileCharSize: Int,
        body: String,
    ): String? {
        val targetChars = maxFileCharSize / 2
        val prompt = """
            请整理以下共享记忆内容,按以下规则压缩:
            1. 去重:相似信息只保留最后一份，但不是简单的裁切，而是语义融合
            2. 去冲突:语义相反信息以最后一份为准，去掉前面冲突的内容
            3. 合并:相关信息合并为更紧凑的表述(如"喜欢海鲜"+"喜欢日料"→"喜欢海鲜与日料")
            4. 保留:所有关键事实必须保留,不能因压缩丢失语义，且保持原有的记忆条目的格式与顺序

            只输出压缩后的正文,不要任何前缀说明。压缩到 $targetChars 字以内。

            原始内容:
            $body
        """.trimIndent()
        val request = ChatRequest(
            messages = listOf(
                ChatMessage.System("你是一个记忆整理助手,只负责按规则压缩用户提供的记忆内容,不回答其他问题。"),
                ChatMessage.User(listOf(ContentPart.Text(prompt))),
            ),
        )
        val summary = runCatching { llmProvider.chat(request).message.content }.getOrNull() ?: return null
        // 无效兜底:压缩结果没有变短(字符数),说明压缩失败,返回 null 让调用方保留原文
        return if (summary.length < body.length) summary else null
    }
}
