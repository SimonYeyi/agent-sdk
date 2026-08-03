package io.github.yeyi.agent.demo.s2s

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.awaitResult
import io.github.yeyi.agent.demo.LlmProviderFactory
import io.github.yeyi.agent.demo.log
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.realtime.Intention
import io.github.yeyi.agent.realtime.IntentionClassifier
import io.github.yeyi.agent.team.BossAgent
import io.github.yeyi.agent.team.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.LinkedList

internal class LlmIntentionClassifier(
    private val capabilities: List<String>,
    private val boss: BossAgent,
    scope: CoroutineScope
) : IntentionClassifier {
    private var uLast: String = ""
    private var aLast: String = ""
    private val focusedTasks: LinkedList<TaskState> = LinkedList()
    private val activatedTasks: List<TaskState> get() = boss.getAllTasks()

    init {
        scope.launch {
            boss.tasksState.collect { tasksState ->
                if (tasksState.terminal) {
                    updateFocusedTasks(tasksState.tasks)
                }
            }
        }
    }

    private fun updateFocusedTasks(tasks: List<TaskState>) {
        tasks.forEach { task ->
            focusedTasks.removeIf { it.taskId == task.taskId }
            focusedTasks.addFirst(task)
        }
        while (focusedTasks.size > MAX_FOCUSED_TASKS) {
            focusedTasks.removeLast()
        }
    }

    private fun findTaskById(taskId: String): TaskState? {
        return focusedTasks.find { it.taskId == taskId }
            ?: activatedTasks.find { it.taskId == taskId }
    }

    private fun renderTasks(): String {
        return buildString {
            if (focusedTasks.isNotEmpty() || activatedTasks.isNotEmpty()) {
                val merged = mutableListOf<TaskState>()
                focusedTasks.forEach { merged.add(it) }
                activatedTasks.filter { act -> merged.none { it.taskId == act.taskId } }
                    .forEach { merged.add(it) }
                merged.forEach {
                    val final = it.events.last() as? AgentEvent.Final
                    appendLine("- taskId: \"${it.taskId}\", task: \"${it.task}\", result: \"${final?.result?.message?.content}\"")
                }
            }
        }
    }

    private fun buildPersona(): Persona {

        return Persona(
            """
            你是车载语音助手的意图分类模块，将用户当前输入所以表达的意图，分类为任务指令（Task）或闲聊（Chat）。

            # 【能力列表】
            ${capabilities.joinToString("、")}

            # 【任务列表】（用于指代消解 / 省略恢复）
            ${renderTasks().let { it.takeIf { it.isNotEmpty() } ?: "- （空）" }}

            # 输出格式（JSON）
            - Task: {"type":"Task","ack":"好的，正在为您处理","content":"清晰的任务内容","anchors":["任务ID"]}"}
            - Chat(无法操作): {"type":"Chat","ack":"抱歉，该操作不支持"}
            - Chat(意图不明): {"type":"Chat","ack":"提出澄清问题"}
            - Chat(闲聊): {"type":"Chat"}

            # 示例（以下任务列表仅用于示例演示）
            ## 示例任务列表
            - taskId: "t1", task: "把空调调到24度"
            - taskId: "t2", task: "打开座椅加热"

            User: "改为26度"
            → {"type":"Task","ack":"好的，正在为您调整","content":"把空调调到26度","anchors":["t1"]}

            User: "座椅太热了"
            → {"type":"Task","ack":"好的，马上为你解决，请稍等","content":"关闭座椅加热","anchors":["t2"]}

            User: "关掉"
            → {"type":"Chat","ack":"请问您想关掉什么呢？空调还是座椅加热"}

            User: "今天天气不错"
            → {"type":"Chat"}

            User: "打开前照灯"
            → {"type":"Chat","ack":"抱歉，该操作不支持"}
        """.trimIndent()
        )
    }

    private suspend fun buildMemory(): Memory {
        val memory = InMemoryMemory()
        if (uLast.isNotBlank()) {
            memory.add(ChatMessage.User(uLast))
            memory.add(ChatMessage.Assistant(aLast))
        }
        return memory
    }

    override suspend fun classify(asr: String): Intention {
        log.info("capabilities: ${capabilities.joinToString("、")}")
        log.info("allTasks: ${renderTasks()}")
        log.info("previous: ${uLast}:${aLast}")
        log.info("current Q: $asr")
        val persona = buildPersona()
        val memory = buildMemory()
        val provider = LlmProviderFactory.create()
        val result = agent {
            persona(persona)
            memory(memory)
            llmProvider(provider)
        }.run(asr).awaitResult()
        val (intention, anchors) = parseIntention(result.message.content!!)
        log.info("current A: ${intention}:$anchors")

        uLast = asr
        aLast = result.message.content!!

        updateFocusedTasks(anchors.mapNotNull { findTaskById(it) })

        return intention
    }

    private data class ParseResult(
        val intention: Intention,
        val anchors: List<String>
    )

    private fun parseIntention(jsonStr: String): ParseResult {
        val element = Json.parseToJsonElement(jsonStr)
        val obj = element.jsonObject
        return when (obj["type"]?.jsonPrimitive?.content) {
            "Task" -> ParseResult(
                Intention.Task(
                    ack = obj["ack"]?.jsonPrimitive?.content!!,
                    content = obj["content"]?.jsonPrimitive?.content!!
                ),
                anchors = obj["anchors"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: emptyList()
            )

            else -> {
                val ack = obj["ack"]?.jsonPrimitive?.content
                ParseResult(Intention.Chat(ack), emptyList())
            }
        }
    }

    companion object {
        private const val MAX_FOCUSED_TASKS = 6
    }
}
