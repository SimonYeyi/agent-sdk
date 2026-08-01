package io.github.yeyi.agent.demo.s2s

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.awaitResult
import io.github.yeyi.agent.demo.LlmProviderFactory
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.realtime.DelegationReply
import io.github.yeyi.agent.realtime.DelegationReply.Confirmation
import io.github.yeyi.agent.realtime.DelegationReply.Failure
import io.github.yeyi.agent.realtime.DelegationReply.Success
import io.github.yeyi.agent.realtime.Intention
import io.github.yeyi.agent.realtime.IntentionClassifier
import io.github.yeyi.agent.realtime.RealtimeDelegation
import io.github.yeyi.agent.realtime.ack
import io.github.yeyi.agent.team.BossAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge

class BossDelegation(private val boss: BossAgent) : RealtimeDelegation {
    override val classifier: IntentionClassifier = object : IntentionClassifier {
        private var q = ""
        private var a = ""

        override suspend fun classify(asr: String): Intention {
            val memory = InMemoryMemory()
                .apply { add(ChatMessage.User(q)) }
                .apply { add(ChatMessage.Assistant(a)) }

            val persona = Persona("你是一个意图分类助手。根据用户输入判断是否属于任务。")
                .extra(capabilities.joinToString("、"), "能力列表")
                .extra(
                    """
                        判断规则：
                        1. 命中能力列表 → type="Task"：
                           - ack 简短确认，content 为具体任务内容。
                        2. 未命中能力列表 → type="Chat"：
                           - 属于明确任务指令（但不在能力范围内），ack 自然表达无法操作
                           - 闲聊/一般咨询 → type="Chat"，ack "null"

                        输出格式（必须严格遵循 JSON）：
                        - 命中：{"type":"Task","ack":"好的，正在为您xxx","content":"具体任务内容"}
                        - 未命中（任务指令）：{"type":"Chat","ack":"表达无法操作"}
                        - 未命中（闲聊）：{"type":"Chat","ack":"null"}

                        示例：
                        输入："帮我把空调调到24度"
                        输出：{"type":"Task","ack":"好的，正在为您调整空调温度","content":"把空调调到24度"}

                        输入："帮我关闭前照灯"
                        输出：{"type":"Chat","ack":"抱歉，这个功能不支持，换个其他需求试试吧。"}

                        输入："今天心情真好"
                        输出：{"type":"Chat","ack":"null"}
                        """.trimIndent()
                )
            val result = agent {
                persona(persona)
                llmProvider(LlmProviderFactory.create())
                memory(memory)
            }.run(asr).awaitResult()

            q = asr
            a = result.message.content ?: ""
            return parseIntention(a)
        }
    }

    private fun parseIntention(json: String): Intention {
        val type = json.substringAfter("\"type\":\"").substringBefore('"')
        return when (type) {
            "Task" -> {
                val ack = json.substringAfter("\"ack\":\"").substringBefore('"')
                val content = json.substringAfter("\"content\":\"").substringBefore('"')
                Intention.Task(ack, content)
            }

            "Chat" -> {
                val ack = json.substringAfter("\"ack\":\"").substringBefore('"')
                Intention.Chat(ack.takeIf { it != "null" })
            }

            else -> Intention.Chat(null)
        }
    }

    override val capabilities: List<String> by lazy {
        listOf("空调控制", "座椅控制", "车窗控制", "氛围灯控制", "导航", "驾驶辅助")
    }

    private val runEvents = MutableSharedFlow<DelegationReply>(extraBufferCapacity = 64)

    override val replies: Flow<DelegationReply> = merge(
        runEvents,
        boss.continuations.mapNotNull { event ->
            when (event) {
                is AgentEvent.Final -> Success(event.result.message.content ?: "")
                is AgentEvent.Failed -> Failure(event.cause.message ?: event.cause.toString())
                else -> null
            }
        },
    )

    override suspend fun run(task: String) {
        var delegated = false
        boss.run(task).collect { event ->
            when (event) {
                is AgentEvent.ToolCallExplanation if (event.toolNames.contains("publish_task")) ->
                    delegated = true

                is AgentEvent.Final if (delegated.not()) ->
                    runEvents.emit(Confirmation(event.result.message.content ?: ""))

                is AgentEvent.Failed -> runEvents.emit(
                    Failure(
                        event.cause.message ?: event.cause.toString()
                    )
                )

                else -> Unit
            }
        }
    }
}
