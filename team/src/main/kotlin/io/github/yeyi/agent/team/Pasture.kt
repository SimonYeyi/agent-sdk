package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.toolset.ToolsetRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class Pasture internal constructor(
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry?,
    private val skillRegistry: SkillRegistry?,
    private val subagentRegistry: SubagentRegistry?,
    private val toolsetRegistry: ToolsetRegistry?,
    private val scope: CoroutineScope,
    private val maxIterations: Int,
    private val maxRounds: Int,
) {
    private val baseRole: String = "You are a helpful worker. Complete the given task and return the result."

    private val runningJobs: MutableMap<String, Job> = mutableMapOf()
    private val jobsLock: Mutex = Mutex()

    // lateinit: 由 [observe] 赋值, 后续 collect / launchBeast 统一从此字段读取.
    // 未初始化访问抛 UninitializedPropertyAccessException (消息固定但可读).
    private lateinit var bulletinBoard: BulletinBoard

    /**
     * 启动后台 collect 协程, 订阅 [bb] 的 publishEvents (TaskAssignment + Cancellation 路径).
     * 返回时**保证** collector 已注册到 `_events` (`subscriptionCount.first {}` 同步等到).
     *
     * 两次 observe 会抛 [IllegalStateException]. 由 [BossAgentBuilder.build] 在构造完成后
     * runBlocking 调用一次.
     */
    internal suspend fun observe(bb: BulletinBoard) {
        check(!::bulletinBoard.isInitialized) { "Pasture.observe() must be called only once" }
        bulletinBoard = bb
        val expected = bulletinBoard.subscriptionCount.value + 1
        scope.launch {
            bulletinBoard.publishEvents.collect { event ->
                when (event) {
                    is TaskAssignment -> handleAssignment(event)
                    is Cancellation -> handleCancellation(event)
                }
            }
        }
        bulletinBoard.subscriptionCount.first { it >= expected }
    }

    private suspend fun handleAssignment(e: TaskAssignment) {
        val beast: Beast = try {
            withContext(Dispatchers.IO) { assembleHorse(e.selections) }
        } catch (_: IllegalStateException) {
            buildOx()
        }
        launchBeast(e, beast)
    }

    private suspend fun launchBeast(e: TaskAssignment, beast: Beast) {
        val userInput = if (e.context.isNullOrBlank()) e.task else "${e.context}\n\n${e.task}"
        val job = scope.launch {
            try {
                beast.run(userInput) { event ->
                    bulletinBoard.progressEvent(TaskUpdate(e.taskId, event))
                }
            } catch (t: Throwable) {
                bulletinBoard.progressEvent(TaskUpdate(e.taskId, AgentEvent.Failed(t)))
                if (t is CancellationException) throw t
            }
        }
        jobsLock.withLock { runningJobs[e.taskId] = job }
        // invokeOnCompletion 的回调不是 suspend; 用 runBlocking 在当前派发线程内
        // 同步获取锁, 保留 jobsLock 保护 runningJobs 的语义.
        job.invokeOnCompletion {
            runBlocking { jobsLock.withLock { runningJobs.remove(e.taskId) } }
        }
    }

    private fun handleCancellation(e: Cancellation) {
        scope.launch {
            val job = jobsLock.withLock { runningJobs[e.taskId] }
            job?.cancel()
        }
    }

    private suspend fun assembleHorse(selections: List<Selection>): Horse {
        if (selections.isEmpty()) error("assembleHorse: selections is empty")
        if (selections.any { it is Selection.Subagent }) error("assembleHorse: selections contains Subagent, fallback to Ox")

        val skillTexts = mutableListOf<String>()
        val tools = mutableListOf<Tool>()

        for (s in selections) {
            when (s) {
                is Selection.Skill -> {
                    val skill = skillRegistry?.all()?.firstOrNull { it.name == s.name }
                        ?: error("assembleHorse: skill not found: ${s.name}")
                    val text = skill.load()
                    skillTexts += text
                    skillRegistry.allTools().forEach { tool ->
                        val pattern = Regex("\\b" + Regex.escape(tool.name) + "\\b")
                        if (pattern.containsMatchIn(text)) tools += tool
                    }
                }

                is Selection.Toolset -> {
                    val toolset = toolsetRegistry?.all()?.firstOrNull { it.name == s.name }
                        ?: error("assembleHorse: toolset not found: ${s.name}")
                    tools += toolset.all()
                }

                is Selection.Tool -> {
                    val tool = toolRegistry?.all()?.firstOrNull { it.name == s.name }
                        ?: error("assembleHorse: tool not found: ${s.name}")
                    tools += tool
                }

                is Selection.Subagent -> { /* unreachable */
                }
            }
        }

        val persona = Persona(
            buildString {
                append(baseRole)
                skillTexts.forEach { append("\n\n").append(it) }
            }
        )

        return Horse(llmProvider, persona, tools, maxIterations, maxRounds)
    }

    private fun buildOx(): Ox = Ox(
        llmProvider = llmProvider,
        persona = Persona(baseRole),
        toolRegistry = toolRegistry,
        skillRegistry = skillRegistry,
        subagentRegistry = subagentRegistry,
        toolsetRegistry = toolsetRegistry,
        maxIterations = maxIterations,
        maxRounds = maxRounds,
    )
}
