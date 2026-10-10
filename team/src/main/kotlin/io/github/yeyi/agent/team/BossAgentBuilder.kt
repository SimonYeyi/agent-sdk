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
import io.github.yeyi.agent.tool.lazy_loading.LazyToolRegistry
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

    private var toolRegistry0: ToolRegistry? = null
    private var lazyToolRegistry0: LazyToolRegistry? = null
    private var toolsetRegistry0: ToolsetRegistry? = null
    private var skillRegistry0: SkillRegistry? = null
    private var subagentRegistry0: SubagentRegistry? = null

    private val baseRole: String = """
        You are the boss of a team. You can:
        1. Respond to chitchat directly.
        2. Handle simple questions using your tools.
        3. Delegate complex tasks to workers (beast) by calling publish_task — see the tool description for available capabilities and how to choose a selection.

        **Transition message rule**: Tasks are executed and cancelled asynchronously by workers.
        When you call publish_task or cancel_task, you MUST write a short present-continuous
        transition message (e.g. "正在为您派发/取消任务，请稍等") IN THE SAME message as the tool call(s).

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
     * 注册直接可用的工具 — 合并进 innerAgent 的 ToolRegistry.
     * LLM 可见可调, 走 boss 同步路径, 无 beast 派发开销.
     *
     * **注意**: 注册的工具由 boss LLM 直接控制 (同步阻塞当前 run), 必须确保
     * 工具执行耗时足够短, 否则会阻塞 boss 的 ReAct 循环.
     */
    public fun tools(registry: ToolRegistry) {
        toolRegistry0 = registry
    }

    /**
     * 注册延迟加载的工具池 — boss 通过 [Selection.Tool] 选用, pasture 解析注入 Horse.
     * 工具通过 [io.github.yeyi.agent.tool.lazy_loading.lazyTools] 机制按需激活.
     */
    public fun lazyTools(registry: LazyToolRegistry) {
        lazyToolRegistry0 = registry
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
            toolRegistry = toolRegistry0,
            lazyToolRegistry = lazyToolRegistry0,
            toolsetRegistry = toolsetRegistry0,
            skillRegistry = skillRegistry0,
            subagentRegistry = subagentRegistry0,
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
            lazyToolRegistry0?.let { reg ->
                put(Selection.Type.Tool.value, reg.all().map { NamedCapability(it.name, it.description) })
            }
            toolsetRegistry0?.let { reg ->
                put(
                    Selection.Type.Toolset.value,
                    reg.all().map { NamedCapability(it.name, it.description) }
                )
            }
            skillRegistry0?.let { reg ->
                put(
                    Selection.Type.Skill.value,
                    reg.all().map { NamedCapability(it.name, it.description) }
                )
            }
            subagentRegistry0?.let { reg ->
                put(
                    Selection.Type.Subagent.value,
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
            toolRegistry0?.let { tools(it.all()) }
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
