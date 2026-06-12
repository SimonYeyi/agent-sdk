package io.github.yeyi.agent.app.demo.skills

import io.github.yeyi.agent.skill.Skill

/**
 * 天气查询 Skill
 * 
 * 提供天气查询能力的文档说明，指导 Agent 如何使用定位和天气工具。
 */
class WeatherSkill : Skill {
    override val name: String = "weather"
    override val description: String = "当需要查询无明确位置的天气情况时使用该技能，如：今天天气如何、明天天气怎样等无明确地点的情况，技能会指导你如何获取准确的位置信息。"

    override fun load(): String = """
        # 天气查询助手
        
        你是一个专业的天气查询助手。当用户询问天气时，请按以下步骤操作：
        
        ## 使用流程
        
        1. **获取位置**：首先调用 `get_location` 工具获取用户当前位置
        
        2. **查询天气**：使用上一步返回的城市名称，调用 `get_weather` 工具
        
        3. **回复用户**：按以下固定格式回复（每项必出，顺序、单位、建议词均不可变），保持简洁自然：

           `{城市}{时段}天气{状况}，温度{数值}°C，湿度{数值}%，风速{数值}km/h，{建议}！`

           - 时段：用「今天」「明天」等词
           - 天气状况：1-2 字简短描述（晴/多云/阴/雨/雪 等）
           - 出行建议：「适宜出行」或「不适宜出行」二选一
        
        ## 注意事项
        
        - 必须先获取位置再查询天气，不要假设用户所在城市
        - 保持回复简洁友好，突出关键信息
    """.trimIndent()
}
