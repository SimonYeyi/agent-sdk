package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.tool.FinalizableTool
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.lazy_loading.LazyTool
import io.github.yeyi.agent.tool.lazy_loading.LazyToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 终态工具短路时 beast 的 Final 正文为 null —— Pasture 需在写入 dag 前补齐,
 * 保证下游 task 的 context 与 BossAgent 汇总都能读到产出。
 */
class PastureFinalContentTest {

    /** 可终结工具: 执行后直接短路, 产出结构化文本, 不触发下一轮推理。 */
    private val CardTool = object : Tool, FinalizableTool {
        override val name = "card"
        override val description = "Produce a card."
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success("CARD_PAYLOAD")
    }

    /** 第一轮调 card, 第二轮是正常文本 Final (给下游 task 用)。 */
    private fun llmResponses(): List<ChatResponse> = listOf(
        ChatResponse(
            message = ChatMessage.Assistant(
                content = null,
                toolCalls = listOf(ToolCall(id = "c1", name = "card", arguments = JsonNull)),
            ),
            usage = null,
            finishReason = FinishReason.ToolCalls,
        ),
        ChatResponse(ChatMessage.Assistant(content = "downstream done"), null, FinishReason.Stop),
    )

    @Test
    fun `terminal-tool Final with null content is backfilled before reaching downstream`() = runBlocking {
        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeLlmProvider(nonStreamResponses = llmResponses())
        val lazyToolReg = LazyToolRegistry().apply { register(LazyTool(CardTool)) }
        val assembler = BeastAssembler(
            llmProvider = fake,
            toolRegistry = null,
            lazyToolRegistry = lazyToolReg,
            toolsetRegistry = null,
            skillRegistry = null,
            subagentRegistry = null,
            maxIterations = 2,
            maxRounds = 5,
        )
        Pasture(assembler = assembler, scope = scope).observe(bb)

        val terminalUpdates = java.util.concurrent.CopyOnWriteArrayList<TaskUpdate>()
        val collector = async(start = CoroutineStart.UNDISPATCHED) {
            bb.progressEvents.filterIsInstance<TaskUpdate>()
                .collect { if (it.event is AgentEvent.Final) terminalUpdates.add(it) }
        }

        bb.publishEvent(TaskAssignments("query", listOf(
            TaskAssignment("t1", Selection.Tool("card"), "make card", null, emptyList()),
            TaskAssignment("t2", Selection.Tool("card"), "use card", null, listOf("t1")),
        )))

        withTimeout(5000) { while (terminalUpdates.none { it.taskId == "t2" }) kotlinx.coroutines.delay(20) }
        collector.cancel()

        // t1 的 Final 必须已补齐 content (来自 card 工具结果)
        val t1Content = terminalUpdates.first { it.taskId == "t1" }.event.let {
            (it as AgentEvent.Final).result.message.content
        }
        assertEquals("CARD_PAYLOAD", t1Content)

        // t2 收到的 userInput 里带上 t1 的产出
        val t2Request = fake.recordedRequests.last()
        val userText = t2Request.messages.filterIsInstance<ChatMessage.User>().last().parts
            .joinToString("\n") { (it as io.github.yeyi.agent.llm.ContentPart.Text).text }
        assertTrue(userText.contains("CARD_PAYLOAD"), "downstream context should carry upstream output, got: $userText")

        scope.cancel()
    }

    @Test
    fun `multi-batch round backfills only the last batch not all tool results`() = runBlocking {
        // iter1: 普通工具(不可终结) → iter2: 终态工具短路。
        // Final.content 为 null, 应只拼最后一批(FINAL_PAYLOAD), 不含早期批次的 OLD_RESULT。
        val plainTool = object : Tool {
            override val name = "plain"
            override val description = "A regular tool."
            override val parametersSchema: ToolParameters = ToolParameters.Empty
            override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
                ToolExecutionResult.success("OLD_RESULT")
        }
        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    message = ChatMessage.Assistant(
                        content = null,
                        toolCalls = listOf(ToolCall(id = "p1", name = "plain", arguments = JsonNull)),
                    ),
                    usage = null,
                    finishReason = FinishReason.ToolCalls,
                ),
                ChatResponse(
                    message = ChatMessage.Assistant(
                        content = null,
                        toolCalls = listOf(ToolCall(id = "c2", name = "card", arguments = JsonNull)),
                    ),
                    usage = null,
                    finishReason = FinishReason.ToolCalls,
                ),
            )
        )
        val lazyToolReg = LazyToolRegistry().apply {
            register(LazyTool(plainTool))
            register(LazyTool(CardTool))
        }
        val assembler = BeastAssembler(
            llmProvider = fake,
            toolRegistry = null,
            lazyToolRegistry = lazyToolReg,
            toolsetRegistry = null,
            skillRegistry = null,
            subagentRegistry = null,
            maxIterations = 5,
            maxRounds = 5,
        )
        Pasture(assembler = assembler, scope = scope).observe(bb)

        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5000) {
                bb.progressEvents.filterIsInstance<TaskUpdate>()
                    .first { it.event is AgentEvent.Final }
            }
        }
        bb.publishEvent(TaskAssignments("query", listOf(
            TaskAssignment("t1", Selection.Tool("card"), "two batches then short-circuit", null, emptyList()),
        )))
        val update = deferred.await()

        val content = (update.event as AgentEvent.Final).result.message.content
        assertTrue(content != null && content.contains("CARD_PAYLOAD"),
            "backfilled content must carry the last batch result, got: $content")
        assertTrue(content != null && !content.contains("OLD_RESULT"),
            "backfilled content must NOT carry earlier batch results, got: $content")

        scope.cancel()
    }

    @Test
    fun `normal Final keeps its own content untouched`() = runBlocking {
        val bb = BulletinBoard()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "plain answer"), null, FinishReason.Stop)
            )
        )
        val assembler = BeastAssembler(
            llmProvider = fake,
            toolRegistry = null,
            lazyToolRegistry = LazyToolRegistry(),
            toolsetRegistry = null,
            skillRegistry = null,
            subagentRegistry = null,
            maxIterations = 2,
            maxRounds = 5,
        )
        Pasture(assembler = assembler, scope = scope).observe(bb)

        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5000) {
                bb.progressEvents.filterIsInstance<TaskUpdate>()
                    .first { it.event is AgentEvent.Final }
            }
        }
        bb.publishEvent(TaskAssignments("query", listOf(
            TaskAssignment("t1", Selection.Tool("nope"), "hello", null, emptyList()),
        )))
        val update = deferred.await()
        assertEquals("plain answer", (update.event as AgentEvent.Final).result.message.content)
        assertNull((update.event as AgentEvent.Final).result.toolCalls.firstOrNull())

        scope.cancel()
    }
}
