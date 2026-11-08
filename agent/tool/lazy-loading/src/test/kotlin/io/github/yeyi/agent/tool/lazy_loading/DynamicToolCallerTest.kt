package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.tool.FinalizableTool
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 可终结裁决经委托层转发：产卡工具的「出卡即终结」在 lazy 工具代理上同样成立。
 *
 * 转发要带目标自己的入参——裁决依据（如 show_ui）在内层 arguments 里。
 */
class DynamicToolCallerTest {

    /** 裁决依据取自自身入参的目标工具，模拟产卡工具按 show_ui 决定是否可终结。 */
    private class DecidingTool(
        override val name: String,
    ) : Tool, FinalizableTool {
        override val description: String = "deciding"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(
            arguments: JsonElement,
            context: ToolExecutionContext
        ): ToolExecutionResult = ToolExecutionResult.success("ok")

        override fun finalizable(arguments: JsonElement, result: ToolExecutionResult): Boolean =
            (arguments as? JsonObject)?.get("show_ui")?.let {
                (it as? JsonPrimitive)?.content == "true"
            } ?: true
    }

    @Test
    fun `finalizable 转发目标裁决并带上目标自己的入参`() {
        val registry = LazyToolRegistry()
        registry.register(LazyTool(DecidingTool("weather"), LazyTool.Level.TOOL))
        val caller = DynamicToolCaller(registry)

        val shown = caller.finalizable(
            buildJsonObject {
                put("tool_name", "weather")
                put("arguments", buildJsonObject { put("show_ui", "true") })
            },
            ToolExecutionResult.success("ok"),
        )
        val hidden = caller.finalizable(
            buildJsonObject {
                put("tool_name", "weather")
                put("arguments", buildJsonObject { put("show_ui", "false") })
            },
            ToolExecutionResult.success("ok"),
        )

        assertTrue(shown)
        assertFalse(hidden)
    }
}
