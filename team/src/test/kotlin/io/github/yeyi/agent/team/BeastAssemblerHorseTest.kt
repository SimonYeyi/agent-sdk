package io.github.yeyi.agent.team

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.skill.Skill
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.subagent.Subagent
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.tool.lazy_loading.LazyTool
import io.github.yeyi.agent.tool.lazy_loading.LazyToolRegistry
import io.github.yeyi.agent.toolset.Toolset
import io.github.yeyi.agent.toolset.ToolsetRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val FINAL_RESPONSE = ChatResponse(
    message = ChatMessage.Assistant(content = "done", toolCalls = emptyList()),
    usage = null,
    finishReason = FinishReason.Stop,
)

private fun fakeTool(name: String) = object : Tool {
    override val name: String = name
    override val description: String = "fake $name"
    override val parametersSchema: ToolParameters = ToolParameters.Empty
    override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
        ToolExecutionResult.success("ok")
}

private fun fakeSkill(name: String, standalone: Boolean, loadResult: String) = object : Skill {
    override val name: String = name
    override val description: String = "fake skill $name"
    override val standalone: Boolean = standalone
    override suspend fun load(): String = loadResult
}

private fun fakeSubagent(name: String, subagentTools: List<Tool>?) = object : Subagent {
    override val name: String = name
    override val description: String = "fake subagent $name"
    override val tools: List<Tool>? = subagentTools
    override val maxIterations: Int? = null
    override val memory: io.github.yeyi.agent.memory.Memory? = null
    override suspend fun load(): String = "subagent instruction for $name"
}

/**
 * 验证 BeastAssembler assembleHorse 时，根据不同 Selection 类型正确组装 Horse。
 *
 * tools 的含义:
 * - tools != null && not empty: 预处理了工具列表，Horse 直接用这些工具，registry 传 null
 * - tools == empty: standalone skill，Horse 用完整 registry
 * - tools == null: 无法预处理（non-standalone skill 或 subagent），Horse 透传所有 registry
 */
class BeastAssemblerHorseTest {

    private fun makeAssembler(
        toolRegistry: ToolRegistry? = null,
        lazyToolRegistry: LazyToolRegistry? = null,
        toolsetRegistry: ToolsetRegistry? = null,
        skillRegistry: SkillRegistry? = null,
        subagentRegistry: SubagentRegistry? = null,
    ): BeastAssembler = BeastAssembler(
        llmProvider = FakeLlmProvider(nonStreamResponses = listOf(FINAL_RESPONSE)),
        toolRegistry = toolRegistry,
        lazyToolRegistry = lazyToolRegistry,
        toolsetRegistry = toolsetRegistry,
        skillRegistry = skillRegistry,
        subagentRegistry = subagentRegistry,
        baseRole = "You are a worker.",
        maxIterations = 1,
        maxRounds = 5,
    )

    private fun getHorseRegistries(assembler: BeastAssembler, selection: Selection): Triple<ToolRegistry?, LazyToolRegistry?, ToolsetRegistry?> {
        val horse = runBlocking { assembler.assemble(selection) }
        assertTrue(horse is Horse, "expected Horse")
        val h = horse as Horse
        val toolRegField = Horse::class.java.getDeclaredField("toolRegistry")
        toolRegField.isAccessible = true
        val lazyToolRegField = Horse::class.java.getDeclaredField("lazyToolRegistry")
        lazyToolRegField.isAccessible = true
        val toolsetRegField = Horse::class.java.getDeclaredField("toolsetRegistry")
        toolsetRegField.isAccessible = true
        return Triple(
            toolRegField.get(h) as? ToolRegistry,
            lazyToolRegField.get(h) as? LazyToolRegistry,
            toolsetRegField.get(h) as? ToolsetRegistry,
        )
    }

    @Test
    fun toolSelectionHorseGetsSingleToolNoLazyOrToolset() = runBlocking {
        val lazyToolReg = LazyToolRegistry().apply {
            register(LazyTool(fakeTool("echo")))
            register(LazyTool(fakeTool("fetch")))
        }
        val assembler = makeAssembler(lazyToolRegistry = lazyToolReg)
        val (toolReg, lazyReg, toolsetReg) = getHorseRegistries(assembler, Selection.Tool("echo"))
        assertNotNull(toolReg)
        assertEquals(1, toolReg.all().size)
        assertEquals("echo", toolReg.all().first().name)
        assertNull(lazyReg)
        assertNull(toolsetReg)
    }

    @Test
    fun toolSelectionNotFoundReturnsOx() = runBlocking {
        val lazyToolReg = LazyToolRegistry().apply { register(LazyTool(fakeTool("echo"))) }
        val assembler = makeAssembler(lazyToolRegistry = lazyToolReg)
        val beast = assembler.assemble(Selection.Tool("nonexistent"))
        assertTrue(beast is Ox, "expected Ox when tool not found")
    }

    @Test
    fun toolsetSelectionHorseGetsAllToolsetTools() = runBlocking {
        val toolsetReg = ToolsetRegistry().apply {
            register(Toolset("weather", "weather tools").apply {
                add(fakeTool("get_weather"))
                add(fakeTool("get_forecast"))
            })
        }
        val assembler = makeAssembler(toolsetRegistry = toolsetReg)
        val (toolReg, lazyReg, toolsetRegResult) = getHorseRegistries(assembler, Selection.Toolset("weather"))
        assertNotNull(toolReg)
        assertEquals(2, toolReg.all().size)
        assertTrue(toolReg.all().any { it.name == "get_weather" })
        assertTrue(toolReg.all().any { it.name == "get_forecast" })
        assertNull(lazyReg)
        assertNull(toolsetRegResult)
    }

    @Test
    fun skillStandaloneHorseGetsNewEmptyToolRegistry() {
        val skillReg = SkillRegistry().apply {
            register(fakeSkill("search", standalone = true, loadResult = "search instructions"))
        }
        val toolReg = ToolRegistry().apply { register(fakeTool("search_tool")) }
        val lazyToolReg = LazyToolRegistry().apply { register(LazyTool(fakeTool("lazy_tool"))) }
        val toolsetReg = ToolsetRegistry().apply {
            register(Toolset("ts1", "ts").apply { add(fakeTool("ts_tool")) })
        }
        val assembler = makeAssembler(
            toolRegistry = toolReg,
            lazyToolRegistry = lazyToolReg,
            toolsetRegistry = toolsetReg,
            skillRegistry = skillReg,
        )
        runBlocking {
            val horse = assembler.assemble(Selection.Skill("search"))
            assertTrue(horse is Horse)
            val h = horse as Horse
            val toolRegField = Horse::class.java.getDeclaredField("toolRegistry")
            toolRegField.isAccessible = true
            val lazyToolRegField = Horse::class.java.getDeclaredField("lazyToolRegistry")
            lazyToolRegField.isAccessible = true
            val toolsetRegField = Horse::class.java.getDeclaredField("toolsetRegistry")
            toolsetRegField.isAccessible = true
            // standalone=true, tools=emptyList() → else branch: new empty ToolRegistry, registries=null
            assertNotNull(toolRegField.get(h) as? ToolRegistry)
            assertEquals(0, (toolRegField.get(h) as ToolRegistry).all().size)
            assertNull(lazyToolRegField.get(h))
            assertNull(toolsetRegField.get(h))
        }
    }

    @Test
    fun skillNonStandaloneHorseGetsRegistriesPassed() {
        val skillReg = SkillRegistry().apply {
            register(fakeSkill("analyzer", standalone = false, loadResult = "analyzer instructions"))
        }
        val toolReg = ToolRegistry().apply { register(fakeTool("analyzer_tool")) }
        val lazyToolReg = LazyToolRegistry().apply { register(LazyTool(fakeTool("lazy_tool"))) }
        val toolsetReg = ToolsetRegistry().apply {
            register(Toolset("ts1", "ts").apply { add(fakeTool("ts_tool")) })
        }
        val assembler = makeAssembler(
            toolRegistry = toolReg,
            lazyToolRegistry = lazyToolReg,
            toolsetRegistry = toolsetReg,
            skillRegistry = skillReg,
        )
        runBlocking {
            val horse = assembler.assemble(Selection.Skill("analyzer"))
            assertTrue(horse is Horse)
            val h = horse as Horse
            val toolRegField = Horse::class.java.getDeclaredField("toolRegistry")
            toolRegField.isAccessible = true
            val lazyToolRegField = Horse::class.java.getDeclaredField("lazyToolRegistry")
            lazyToolRegField.isAccessible = true
            val toolsetRegField = Horse::class.java.getDeclaredField("toolsetRegistry")
            toolsetRegField.isAccessible = true
            // non-standalone, tools=null → registries passed through
            assertNotNull(toolRegField.get(h) as? ToolRegistry)
            assertNotNull(lazyToolRegField.get(h) as? LazyToolRegistry)
            assertNotNull(toolsetRegField.get(h) as? ToolsetRegistry)
        }
    }

    @Test
    fun skillSelectionNotFoundReturnsOx() = runBlocking {
        val skillReg = SkillRegistry().apply {
            register(fakeSkill("search", standalone = true, loadResult = ""))
        }
        val assembler = makeAssembler(skillRegistry = skillReg)
        val beast = assembler.assemble(Selection.Skill("nonexistent"))
        assertTrue(beast is Ox, "expected Ox when skill not found")
    }

    @Test
    fun subagentWithToolsHorseGetsSubagentToolsNoRegistries() = runBlocking {
        val subagentReg = SubagentRegistry().apply {
            register(fakeSubagent("reviewer", subagentTools = listOf(fakeTool("code_review"), fakeTool("lint"))))
        }
        val assembler = makeAssembler(subagentRegistry = subagentReg)
        val (toolReg, lazyReg, toolsetReg) = getHorseRegistries(assembler, Selection.Subagent("reviewer"))
        assertNotNull(toolReg)
        assertEquals(2, toolReg.all().size)
        assertTrue(toolReg.all().any { it.name == "code_review" })
        assertTrue(toolReg.all().any { it.name == "lint" })
        assertNull(lazyReg)
        assertNull(toolsetReg)
    }

    @Test
    fun subagentWithoutToolsHorseGetsRegistriesPassed() {
        val subagentReg = SubagentRegistry().apply {
            register(fakeSubagent("helper", subagentTools = null))
        }
        val toolReg = ToolRegistry().apply { register(fakeTool("tool_a")) }
        val lazyToolReg = LazyToolRegistry().apply { register(LazyTool(fakeTool("lazy_a"))) }
        val toolsetReg = ToolsetRegistry().apply {
            register(Toolset("ts1", "ts").apply { add(fakeTool("ts_a")) })
        }
        val assembler = makeAssembler(
            toolRegistry = toolReg,
            lazyToolRegistry = lazyToolReg,
            toolsetRegistry = toolsetReg,
            subagentRegistry = subagentReg,
        )
        runBlocking {
            val horse = assembler.assemble(Selection.Subagent("helper"))
            assertTrue(horse is Horse)
            val h = horse as Horse
            val toolRegField = Horse::class.java.getDeclaredField("toolRegistry")
            toolRegField.isAccessible = true
            val lazyToolRegField = Horse::class.java.getDeclaredField("lazyToolRegistry")
            lazyToolRegField.isAccessible = true
            val toolsetRegField = Horse::class.java.getDeclaredField("toolsetRegistry")
            toolsetRegField.isAccessible = true
            assertNotNull(toolRegField.get(h) as? ToolRegistry)
            assertNotNull(lazyToolRegField.get(h) as? LazyToolRegistry)
            assertNotNull(toolsetRegField.get(h) as? ToolsetRegistry)
        }
    }

    @Test
    fun subagentSelectionNotFoundReturnsOx() = runBlocking {
        val subagentReg = SubagentRegistry().apply {
            register(fakeSubagent("reviewer", subagentTools = null))
        }
        val assembler = makeAssembler(subagentRegistry = subagentReg)
        val beast = assembler.assemble(Selection.Subagent("nonexistent"))
        assertTrue(beast is Ox, "expected Ox when subagent not found")
    }

    @Test
    fun toolsetSelectionNotFoundReturnsOx() = runBlocking {
        val toolsetReg = ToolsetRegistry().apply {
            register(Toolset("weather", "weather tools").apply { add(fakeTool("get_weather")) })
        }
        val assembler = makeAssembler(toolsetRegistry = toolsetReg)
        val beast = assembler.assemble(Selection.Toolset("nonexistent"))
        assertTrue(beast is Ox, "expected Ox when toolset not found")
    }
}
