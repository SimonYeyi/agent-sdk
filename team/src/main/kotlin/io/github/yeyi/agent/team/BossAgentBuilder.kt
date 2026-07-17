package io.github.yeyi.agent.team

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
    private var memory0: Memory? = null
    private var llmProvider0: LlmProvider? = null
    private var maxIterations0: Int = 20
    private var maxRounds0: Int = 20

    private var delegatedToolRegistry0: ToolRegistry? = null
    private var quickToolRegistry0: ToolRegistry? = null
    private var skillRegistry0: SkillRegistry? = null
    private var subagentRegistry0: SubagentRegistry? = null
    private var toolsetRegistry0: ToolsetRegistry? = null

    private var bossPersona0: Persona? = null

    private val baseRole: String = """
        You are the boss of a team. You can:
        1. Respond to chitchat directly.
        2. Handle simple questions using your tools.
        3. Delegate complex tasks to workers (beast) by calling publish_task — see the tool description for available capabilities and how to specify selections.
    """.trimIndent()

    public fun memory(value: Memory) { memory0 = value }
    public fun llmProvider(value: LlmProvider) { llmProvider0 = value }
    public fun maxIterations(value: Int) { maxIterations0 = value }
    public fun maxRounds(value: Int) { maxRounds0 = value }

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

    public fun skills(registry: SkillRegistry) { skillRegistry0 = registry }
    public fun subagents(registry: SubagentRegistry) { subagentRegistry0 = registry }
    public fun toolsets(registry: ToolsetRegistry) { toolsetRegistry0 = registry }

    public fun mcps(registry: McpRegistry) {
        @Suppress("UNUSED_PARAMETER") registry
    }

    public fun persona(persona: Persona) {
        require(persona.role.isBlank()) {
            "Persona.role is reserved by the BossAgent framework — must be blank. " +
                "Use personality / domain / constraints / extra to customize agent persona."
        }
        bossPersona0 = persona
    }

    public fun build(): BossAgent {
        val llm = requireNotNull(llmProvider0) { "llmProvider must be set" }
        val mem = requireNotNull(memory0) { "memory must be set" }

        val bulletinBoard = BulletinBoard()
        val bossScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val pasture = Pasture(
            llmProvider = llm,
            toolRegistry = delegatedToolRegistry0,
            skillRegistry = skillRegistry0,
            subagentRegistry = subagentRegistry0,
            toolsetRegistry = toolsetRegistry0,
            scope = bossScope,
            maxIterations = maxIterations0,
            maxRounds = maxRounds0,
        )
        val boss = buildBoss(mem, llm, bulletinBoard, bossScope)

        // 短阻塞当前线程, 等订阅 collector 就位 (subscriptionCount 同步等到, 几 ms 完成).
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
                put("tool", reg.all().map { NamedCapability(it.name, it.description) })
            }
            skillRegistry0?.let { reg ->
                put("skill", reg.all().map { NamedCapability(it.name, it.description) })
            }
            subagentRegistry0?.let { reg ->
                put("subagent", reg.all().map { NamedCapability(it.name, it.description) })
            }
            toolsetRegistry0?.let { reg ->
                put("toolset", reg.all().map { NamedCapability(it.name, it.description) })
            }
        }

        val publishTask = PublishTaskTool(bulletinBoard, capabilitiesByType)
        val cancelTask = CancelTaskTool(bulletinBoard)
        val persona = buildPersona()

        val innerAgent = agent {
            persona(persona)
            llmProvider(llmProvider)
            memory(memory, maxRounds0)
            tool(publishTask)
            tool(cancelTask)
            quickToolRegistry0?.let { tools(it) }
            maxIterations(maxIterations0)
        }

        return BossAgent(innerAgent, scope)
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