package io.github.yeyi.agent.memory.shared

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 读出共享记忆的工具。
 *
 * description 每轮求值(用 getter 而非 `by lazy`):拼出当前 loader 快照中所有记忆的
 * name + description 列表,使 reload 后下一轮 LLM 自动看到新记忆。
 *
 * execute 只调一句 [SharedMemoryLoader.load]:锁内读正文 + 刷新访问时间,不判淘汰;
 * 完成后 loader 内部自动 reload,条目超出 `maxFileCount` 时淘汰其他最久未访问的记忆。
 */
internal class SharedMemoryLoadTool(
    private val loader: SharedMemoryLoader,
) : Tool {
    override val name: String = "load_shared_memory"

    override val description: String
        get() = buildString {
            appendLine("加载共享记忆。")
            appendLine("当前可用的记忆文件:")
            val memories = loader.all()   // 只取一次快照:判空与遍历必须是同一份
            if (memories.isEmpty()) {
                appendLine("(暂无)")
            } else {
                memories.forEach { m ->
                    appendLine("- ${m.name}：${m.description}")
                }
            }
            appendLine("传入 name 调用,记忆按需加载入上下文。")
        }.trimEnd()

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        val name = (arguments.jsonObject["name"] as? JsonPrimitive)?.content
            ?: return ToolExecutionResult.error("Missing 'name'")

        // 保留名拦截:user.md 不在 list() 里,但 loader.load 直读文件、不查快照,
        // 只靠快照排除挡不住它,必须在工具层显式拒绝
        if (name == SharedMemory.RESERVED_USER) {
            return ToolExecutionResult.error(
                "'${SharedMemory.RESERVED_USER}' is the auto-injected user profile and is not in the memory list; " +
                    "its content is already in your system prompt. Use save_shared_memory to update it."
            )
        }

        // 锁内读正文 + 刷新 lastAccessedAt,不判淘汰;loader 随后自动 reload
        val content = loader.load(name)
            ?: return ToolExecutionResult.error("Memory not found: $name (it may have been forgotten)")

        return ToolExecutionResult.success(content)
    }
}
