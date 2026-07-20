package io.github.yeyi.agent.team

import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.toolset.Toolset
import io.github.yeyi.agent.toolset.ToolsetRegistry
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun tool(name: String): Tool = object : Tool {
    override val name: String = name
    override val description: String = "fake $name"
    override val parametersSchema = ToolParameters.Empty
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
        ToolExecutionResult("ok")
}

class BeastAssemblerTest {

    private fun assembler(
        toolReg: ToolRegistry? = null,
        toolsetReg: ToolsetRegistry? = null,
        skillReg: SkillRegistry? = null,
    ): BeastAssembler = BeastAssembler(
        llmProvider = io.github.yeyi.agent.fakes.FakeLlmProvider(),
        toolRegistry = toolReg,
        skillRegistry = skillReg,
        subagentRegistry = null,
        toolsetRegistry = toolsetReg,
        baseRole = "test",
        maxIterations = 1,
        maxRounds = 1,
    )

    @Test
    fun `mentioned tool name in skill text is returned`() {
        val fetchUrl = tool("fetch_url")
        val reg = ToolRegistry().apply { register(fetchUrl) }
        val a = assembler(toolReg = reg)

        val matched = a.extractTools("call fetch_url to get data")

        assertEquals(listOf("fetch_url"), matched.map { it.name })
    }

    @Test
    fun `unmentioned tool name is not returned`() {
        val fetchUrl = tool("fetch_url")
        val reg = ToolRegistry().apply { register(fetchUrl) }
        val a = assembler(toolReg = reg)

        val matched = a.extractTools("do something else entirely")

        assertTrue(matched.isEmpty(), "expected no tools, got: ${matched.map { it.name }}")
    }

    @Test
    fun `mentioned toolset name flattens all sub-tools into result`() {
        val reg = ToolsetRegistry().apply {
            register(
                Toolset("weather", "weather tools").apply {
                    add(tool("get_weather"))
                    add(tool("get_forecast"))
                }
            )
        }
        val a = assembler(toolsetReg = reg)

        val matched = a.extractTools("call weather to get forecast")

        assertEquals(
            setOf("get_weather", "get_forecast"),
            matched.map { it.name }.toSet(),
            "toolset 命中应展平所有子 Tool"
        )
    }

    @Test
    fun `sub-tool name does not trigger parent toolset`() {
        // 池子第一层是 Toolset 名 ("weather"), 不是子 Tool 名 ("get_weather").
        // 文本提 "get_weather" 不应触发 weather toolset.
        val reg = ToolsetRegistry().apply {
            register(
                Toolset("weather", "weather tools").apply {
                    add(tool("get_weather"))
                }
            )
        }
        val a = assembler(toolsetReg = reg)

        val matched = a.extractTools("call get_weather directly")

        assertTrue(matched.isEmpty(), "sub-tool name 不应触发, got: ${matched.map { it.name }}")
    }

    @Test
    fun `same name across registries is deduped in flat result`() {
        // 跨 registry 同名在 pool 层不冲突 (tool 与 toolset 可以同名), 但 flatMap
        // 后的 Tool 列表必须去重 — 同名 Tool 只保留首个, 跟 toolRegistry.register
        // 拒绝同名语义一致.
        val echoInToolReg = tool("echo")
        val echoInSkillReg = tool("echo")
        val toolReg = ToolRegistry().apply { register(echoInToolReg) }
        val skillReg = SkillRegistry().apply { registerTools(listOf(echoInSkillReg)) }
        val a = assembler(toolReg = toolReg, skillReg = skillReg)

        val matched = a.extractTools("use echo")

        assertEquals(1, matched.size, "flatMap 后同名 Tool 应去重, got: ${matched.map { it.name }}")
        assertEquals("echo", matched.single().name)
        assertTrue(matched.single() === echoInToolReg, "应保留 buildList 首次出现的 (toolRegistry 先)")
    }
}