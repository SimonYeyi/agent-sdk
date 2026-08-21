package io.github.yeyi.agent.modality

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.memory.MediaArchive

/**
 * 多模态消息适配器,在 LLM 请求边界做三件事:
 *
 * 1. **拆末条 ToolResult**: 含 media 时拆成 text-only + 合成的 User ([ChatMessage.ToolResult.adaptModality])
 * 2. **找最后 User**: 从 messages 找到最后一条 User 的索引
 * 3. **渲染**:
 *    - 末条 User → [MediaArchive.resolve] 把 Local 转 Data, 其他 media 透传
 *    - 其他消息 → [io.github.yeyi.agent.toTextMessage] 把 media 转 `[image] local fileId=xxx` 占位文本
 *
 * Adapter **不依赖**整个 [io.github.yeyi.agent.memory.Memory], 只通过 [MediaArchive] 拿读桥。
 * 这是纯变换接口, IO 通过 [MediaArchive] 注入; 测试里直接 lambda mock。
 *
 * `MediaArchive` 放在方法签名而非构造器 —— 适配工作的核心就是处理归档, 显式化在契约里:
 * caller 一眼看得出"做适配需要 archive", 实现类无隐藏状态。
 *
 * 注意: 本接口使用普通 `interface` 而非 `fun interface`, 为未来扩展留口子(如
 * `shouldAdapt(message): Boolean` 旁路控制等); fun interface 一旦后续加方法就被锁死,
 * 不值得为"现在能 lambda 构造"换掉未来灵活性。
 */
public interface ModalityAdapter {
    public suspend fun adapt(messages: List<ChatMessage>, archive: MediaArchive): List<ChatMessage>
}
