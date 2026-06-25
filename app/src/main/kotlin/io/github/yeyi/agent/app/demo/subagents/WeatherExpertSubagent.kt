package io.github.yeyi.agent.app.demo.subagents

import io.github.yeyi.agent.app.demo.tools.GetLocationTool
import io.github.yeyi.agent.app.demo.tools.GetWeatherTool
import io.github.yeyi.agent.subagent.SimpleSubagent

/**
 * 天气查询专家 Subagent。
 *
 * 工具集封闭为 GetLocationTool + GetWeatherTool;主 agent 端不挂载 WeatherSkill,
 * 以避免主 agent 与本 subagent 任务域冲突。
 */
class WeatherExpertSubagent : SimpleSubagent(
    name = "weather",
    description = "天气查询专家,获取用户位置并按固定格式返回天气信息",
    tools = listOf(GetLocationTool(), GetWeatherTool()),
) {
    override fun loadInstructions(): String = """
        # 天气查询助手

        你是一个专业的天气查询助手。当收到任务时,按以下流程操作:

        ## 使用流程

        1. **获取位置**: 调用 `get_location` 工具获取用户当前位置
        2. **查询天气**: 使用上一步的城市名称,调用 `get_weather` 工具
        3. **回复用户**: 按以下固定格式回复(每项必出,顺序、单位、建议词均不可变):

           `{城市}{时段}天气{状况},温度{数值}°C,湿度{数值}%,风速{数值}km/h,{建议}!`

           - 时段:用「今天」「明天」等词
           - 天气状况:1-2 字简短描述(晴/多云/阴/雨/雪 等)
           - 出行建议:「适宜出行」或「不适宜出行」二选一

        ## 注意事项

        - 必须先获取位置再查询天气,不要假设用户所在城市
        - 保持回复简洁友好,突出关键信息
    """.trimIndent()
}
