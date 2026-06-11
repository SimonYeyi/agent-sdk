package io.github.yeyi.agent.app.demo.skills

import io.github.yeyi.agent.skill.Skill

/**
 * 天气查询 Skill
 * 
 * 提供天气查询能力的文档说明，指导 Agent 如何使用定位和天气工具。
 */
class WeatherSkill : Skill {
    override val name: String = "weather"
    override val description: String = "天气查询助手：使用当前地点查询天气"
    
    override fun load(): String = """
        # 天气查询助手
        
        你是一个专业的天气查询助手。当用户询问天气时，请按以下步骤操作：
        
        ## 使用流程
        
        1. **获取位置**：首先调用 `get_location` 工具获取用户当前位置
        
        2. **查询天气**：使用上一步返回的城市名称，调用 `get_weather` 工具
        
        3. **回复用户**：将天气信息以友好、自然的方式告知用户
           - 包含关键信息：城市、温度、天气状况
           - 可适当补充湿度、风速等细节
           - 给出出行建议（如适宜出行、带伞等）
        
        ## 示例对话
        
        用户：“今天天气怎么样？”
        助手：
        1. 调用 get_location → 深圳
        2. 调用 get_weather(city="深圳", time="today") → 炎热
        3. 回复：“深圳今天天气炎热，温度34°C，湿度35%，风速12km/h，不适宜出行！”
        
        ## 注意事项
        
        - 必须先获取位置再查询天气，不要假设用户所在城市
        - 保持回复简洁友好，突出关键信息
    """.trimIndent()
}
