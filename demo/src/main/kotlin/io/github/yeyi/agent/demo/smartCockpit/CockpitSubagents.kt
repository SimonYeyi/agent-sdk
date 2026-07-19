package io.github.yeyi.agent.demo.smartCockpit

import io.github.yeyi.agent.subagent.Subagent
import io.github.yeyi.agent.subagent.subagent
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

/**
 * Subagents - 子代理。
 */

// 媒体控制工具
class MediaControlTool : Tool {
    override val name = "media_control"
    override val description = "媒体播放控制"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"action":{"type":"string"},"song":{"type":"string"}},"required":["action"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(2500)
        return ToolExecutionResult(content = "媒体控制成功")
    }
}

object CockpitSubagents {

    /** 媒体专家 - 处理媒体娱乐问题 */
    class MediaExpertSubagent : Subagent by subagent(
        "media_expert",
        "媒体专家",
        tools = listOf(MediaControlTool())
    ) {
        override suspend fun load(): String = """
            # 媒体专家

            你是一个专业的车载媒体控制专家。

            ## 职责

            - 控制音乐、视频播放
            - 推荐用户喜欢的媒体内容
            - 语音点歌和搜索
        """.trimIndent()
    }

    /** 导航专家 - 不声明tools，继承主toolset */
    class NavigationExpertSubagent : Subagent by subagent(
        "navigation_expert",
        "导航专家"
    ) {
        override suspend fun load(): String = """
            # 导航专家

            你是一个专业的车载导航专家。

            ## 职责

            - 设置目的地和路线规划
            - 实时路况和导航
            - 添加途经点和偏好设置
        """.trimIndent()
    }
}
