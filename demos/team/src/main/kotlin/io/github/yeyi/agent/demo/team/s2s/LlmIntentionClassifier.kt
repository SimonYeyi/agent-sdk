package io.github.yeyi.agent.demo.team.s2s

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.awaitResult
import io.github.yeyi.agent.demo.team.LlmProviderFactory
import io.github.yeyi.agent.demo.team.log
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.realtime.Intention
import io.github.yeyi.agent.realtime.IntentionClassifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class LlmIntentionClassifier(
    private val capabilities: List<String>,
    private val getAllTasks: () -> List<String>,
) : IntentionClassifier {

    private fun buildPersona(chatHistories: List<String>): Persona {
        return Persona(
            """
            你是车载语音的意图分类助手，职责是将用户输入判别为任务指令（Task）或闲聊（Chat）。

            # 【能力列表】（用于判断是否在能力范围内）
            ${capabilities.joinToString("、")}

            # 【事件序列】（用于指代消解与省略恢复）
            ${chatHistories.take(20).joinToString("\n") { it.replace(Regex("\\s+"), " ").trim() }}

            # 【活跃任务】
            ${getAllTasks().joinToString("\n")}

            # 输出格式（JSON）
            - Task: {"type":"Task","ack":"好的，正在为您处理","content":"清晰的任务内容"}
            - Chat(不在能力范围内): {"type":"Chat","ack":"自然表达无法操作"}
            - Chat(意图不明): {"type":"Chat","ack":"提出澄清问题"}
            - Chat(闲聊): {"type":"Chat"}

            # 示例
            User: "把空调开到24度吧"
            → {"type":"Task","ack":"好的，正在为您处理","content":"把空调调到24度"}

            User: "改为26度"
            → {"type":"Task","ack":"好的，正在为您调整","content":"把空调调到26度"}

            User: "打开前照灯"
            → {"type":"Chat","ack":"抱歉，这个操作不支持呢"}

            User: "关掉"
            → {"type":"Chat","ack":"请问您想关掉什么呢？空调还是座椅加热"}

            User: "今天天气不错"
            → {"type":"Chat"}
        """.trimIndent()
        )
    }

    private suspend fun buildMemory(asr: String): Memory =
        InMemoryMemory().apply { add(ChatMessage.User(listOf(ContentPart.Text(asr)))) }

    override val timeout: Long = 3000

    override suspend fun classify(asr: String, chatHistories: List<String>): Intention {
        log.info("capabilities: $capabilities")
        log.info("activeTasks: ${getAllTasks()}")
        log.info("histories: $chatHistories")
        log.info("current Q: $asr")
        val persona = buildPersona(chatHistories)
        val memory = buildMemory(asr)
        val provider = LlmProviderFactory.create()
        val result = agent {
            persona(persona)
            memory(memory)
            llmProvider(provider)
        }.run(AgentQuery.text(asr)).awaitResult()
        log.info("current A: ${result.message.content}")
        val intention = parseIntention(result.message.content!!)
        return intention
    }

    private fun parseIntention(jsonStr: String): Intention {
        val obj = Json.parseToJsonElement(jsonStr).jsonObject
        val type = obj["type"]?.jsonPrimitive?.content
        val ack: JsonElement? = obj["ack"]
        val content: JsonElement? = obj["content"]
        return when (type) {
            "Task" -> Intention.Task(
                ack = ack?.jsonPrimitive?.content!!,
                content = content?.jsonPrimitive?.content!!
            )

            else -> Intention.Chat(ack?.jsonPrimitive?.content)
        }
    }
}
