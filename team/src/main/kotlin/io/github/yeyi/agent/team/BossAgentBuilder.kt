package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.mcp.McpRegistry
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.toolset.ToolsetRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

public class BossAgentBuilder internal constructor() {
    private companion object {
        /** 系统汇报标记 — 任务完成后由 worker 汇报结果时使用，放在用户消息格式中 */
        const val SYSTEM_REPORT_MARKER: String = "[系统汇报]"
    }

    private var bossPersona0: Persona? = null
    private var memory0: Memory? = null
    private var maxRounds0: Int = 20
    private var llmProvider0: LlmProvider? = null
    private var maxIterations0: Int = 20
    private var hook0: AgentHook? = null

    private var delegatedToolRegistry0: ToolRegistry? = null
    private var quickToolRegistry0: ToolRegistry? = null
    private var toolsetRegistry0: ToolsetRegistry? = null
    private var skillRegistry0: SkillRegistry? = null
    private var subagentRegistry0: SubagentRegistry? = null

    private val baseRole: String = """
        You are the boss of a team. You can:
        1. Respond to chitchat directly.
        2. Handle simple questions using your tools.
        3. Delegate complex tasks to workers (beast) by calling publish_task — see the tool description for available capabilities and how to specify selections.

        **Tense rule for task delegation and cancellation**: Tasks are executed and cancelled asynchronously by workers. When you report the status of a delegated task or a cancellation to the user, you MUST use present continuous tense (进行时), NOT perfect tense. For example:
          - ✅ "正在为您调暗客厅灯，请稍等" (present continuous — publishing)
          - ✅ "正在取消客厅灯调节任务" (present continuous — cancelling)
          - ❌ "已为您调暗客厅灯" (perfect tense — wrong, the task is still running)
          - ❌ "已取消客厅灯调节任务" (perfect tense — wrong, the cancellation is still in progress)
        This applies whenever a task is published or cancelled: describe what is happening NOW, not what has finished..

        **About $SYSTEM_REPORT_MARKER**: When you see "$SYSTEM_REPORT_MARKER" at the beginning of a user message,
        it is NOT a real user input — it is a system report from a worker about finished task results.
        Treat it as an internal status update, not as if the user said something.
    """.trimIndent()

    public fun persona(persona: Persona) {
        require(persona.role.isBlank()) {
            "Persona.role is reserved by the BossAgent framework — must be blank. " +
                    "Use personality / domain / constraints / extra to customize agent persona."
        }
        bossPersona0 = persona
    }

    public fun hook(value: AgentHook) {
        hook0 = value
    }

    public fun memory(memory: Memory, maxRounds: Int) {
        memory0 = memory; maxRounds0 = maxRounds
    }

    public fun llmProvider(value: LlmProvider) {
        llmProvider0 = value
    }

    public fun maxIterations(value: Int) {
        maxIterations0 = value
    }

    /**
     * 注册 tool 池 — boss 通过 [Selection.Tool] 选用, pasture 解析注入 Horse.
     */
    public fun tools(registry: ToolRegistry) {
        delegatedToolRegistry0 = registry
    }

    /**
     * 注册 boss 可快速调的工具 — 合并进 innerAgent 的 ToolRegistry.
     * LLM 可见可调, 走 boss 同步路径, 无 beast 派发开销.
     *
     * **注意**: 注册的工具由 boss LLM 直接控制 (同步阻塞当前 run), 必须确保
     * 工具执行耗时足够短, 否则会阻塞 boss 的 ReAct 循环.
     */
    public fun quickTools(registry: ToolRegistry) {
        quickToolRegistry0 = registry
    }

    public fun toolsets(registry: ToolsetRegistry) {
        toolsetRegistry0 = registry
    }

    public fun skills(registry: SkillRegistry) {
        skillRegistry0 = registry
    }

    public fun subagents(registry: SubagentRegistry) {
        subagentRegistry0 = registry
    }

    public fun mcps(registry: McpRegistry) {
        @Suppress("UNUSED_PARAMETER") registry
    }

    public fun build(): BossAgent {
        val llm = requireNotNull(llmProvider0) { "llmProvider must be set" }
        val mem = requireNotNull(memory0) { "memory must be set" }

        val bulletinBoard = BulletinBoard()
        val bossScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val assembler = BeastAssembler(
            llmProvider = llm,
            toolRegistry = delegatedToolRegistry0,
            skillRegistry = skillRegistry0,
            subagentRegistry = subagentRegistry0,
            toolsetRegistry = toolsetRegistry0,
            baseRole = "You are a helpful worker. Complete the given task and return the result.",
            maxIterations = maxIterations0,
            maxRounds = maxRounds0,
        )
        val pasture = Pasture(
            assembler = assembler,
            scope = bossScope,
        )
        val boss = buildBoss(mem, llm, bulletinBoard, bossScope)

        // 短阻塞当前线程, 等订阅 collector 就位 (attach/observe 内部用 onSubscription +
        // CompletableDeferred 同步等到自己的 collector 注册, 几 ms 完成).
        // 调用方拿到的 BossAgent 已"开箱即用", 不再有"构造返回 ≠ 订阅就位"的隐性 race.
        runBlocking {
            pasture.observe(bulletinBoard)
            boss.attach(bulletinBoard)
        }

        return boss
    }

    private fun buildBoss(
        memory: Memory,
        llmProvider: LlmProvider,
        bulletinBoard: BulletinBoard,
        scope: CoroutineScope,
    ): BossAgent {
        val capabilitiesByType: Map<String, List<NamedCapability>> = buildMap {
            delegatedToolRegistry0?.let { reg ->
                put(Selection.Tool.TYPE, reg.all().map { NamedCapability(it.name, it.description) })
            }
            toolsetRegistry0?.let { reg ->
                put(
                    Selection.Toolset.TYPE,
                    reg.all().map { NamedCapability(it.name, it.description) }
                )
            }
            skillRegistry0?.let { reg ->
                put(
                    Selection.Skill.TYPE,
                    reg.all().map { NamedCapability(it.name, it.description) }
                )
            }
            subagentRegistry0?.let { reg ->
                put(
                    Selection.Subagent.TYPE,
                    reg.all().map { NamedCapability(it.name, it.description) }
                )
            }
        }

        val persona = buildPersona()
        val publishTask = PublishTaskTool(bulletinBoard, capabilitiesByType)
        val cancelTask = CancelTaskTool(bulletinBoard)

        val innerAgent = agent {
            persona(persona)
            llmProvider(llmProvider)
            memory(memory, maxRounds0)
            maxIterations(maxIterations0)
            hook0?.let { hook(it) }
            tool(publishTask)
            tool(cancelTask)
            quickToolRegistry0?.let { tools(it) }
        }

        return BossAgent(innerAgent, SYSTEM_REPORT_MARKER, scope)
    }

    private fun buildPersona(): Persona {
        val extra = bossPersona0
        return if (extra == null) {
            Persona(baseRole)
        } else {
            Persona(baseRole).extra(extra.toString())
        }
    }
}

public fun bossAgent(block: BossAgentBuilder.() -> Unit): BossAgent =
    BossAgentBuilder().apply(block).build()
