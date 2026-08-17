package io.github.yeyi.agent.demo.team.smartHome

import io.github.yeyi.agent.subagent.Subagent
import io.github.yeyi.agent.subagent.subagent
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

/**
 * Subagents - 子代理，独立循环处理复杂任务。
 */

// 安防专家工具
class SecurityMonitorTool : Tool {
    override val name = "security_monitor"
    override val description = "安防监控，检测异常"
    override val parametersSchema: ToolParameters = ToolParameters.Empty
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(3000)
        return ToolExecutionResult.success("安防监控正常")
    }
}

class SecurityAlertTool : Tool {
    override val name = "security_alert"
    override val description = "发送安防警报"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """{"type":"object","properties":{"type":{"type":"string"},"message":{"type":"string"}},"required":["type"]}"""
    )
    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        delay(2000)
        return ToolExecutionResult.success("警报已发送")
    }
}

object SmartHomeSubagents {

    /** 安防专家 - 处理安防相关问题 */
    class SecurityExpertSubagent : Subagent by subagent(
        "security_expert",
        "安防专家",
        tools = listOf(SecurityMonitorTool(), SecurityAlertTool())
    ) {
        override suspend fun load(): String = """
            # 安防专家

            你是一个专业的智能家居安防专家。

            ## 职责

            - 监控家中的安防设备状态
            - 发现异常时及时警报
            - 处理用户的安全相关咨询
        """.trimIndent()
    }

    /** 环境调控专家 - 不声明tools，继承主toolset */
    class EnvironmentExpertSubagent : Subagent by subagent(
        "environment_expert",
        "环境调控专家"
    ) {
        override suspend fun load(): String = """
            # 环境调控专家

            你是一个专业的智能家居环境调控专家。

            ## 职责

            - 调节室内温度、灯光、窗帘等
            - 创造舒适的居住环境
            - 响应用户的舒适度需求
        """.trimIndent()
    }
}
