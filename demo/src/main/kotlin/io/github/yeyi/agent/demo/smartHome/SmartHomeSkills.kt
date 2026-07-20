package io.github.yeyi.agent.demo.smartHome

import io.github.yeyi.agent.skill.Skill

/**
 * SmartHome Skills - 场景指导文档。
 */

// 晚安场景：用户说"晚安"、"睡觉了"时加载
class GoodNightSkill : Skill {
    override val name: String = "good_night"
    override val description: String = "用户说晚安、睡觉了时触发"

    override suspend fun load(): String = """
        # 晚安场景

        加载工具集 home_control
        1. 关闭所有灯光
        2. 关闭所有窗帘
        3. 空调调至26°C
    """.trimIndent()
}

// 早安场景：用户说"早安"、"起床了"时加载
class GoodMorningSkill : Skill {
    override val name: String = "good_morning"
    override val description: String = "用户说早安、起床了时触发"

    override suspend fun load(): String = """
        # 早安场景

        加载工具集 home_control
        1. 打开卧室灯光
        2. 打开窗帘
        3. 空调调至24°C
    """.trimIndent()
}

// 离家场景：用户说"出门了"、"我走了"时加载
class LeaveHomeSkill : Skill {
    override val name: String = "leave_home"
    override val description: String = "用户说出門了、我走了时触发"

    override suspend fun load(): String = """
        # 离家场景

        加载工具集 home_control, appliance_control
        1. 关闭所有灯光
        2. 关闭所有窗帘
        3. 关闭空调
        4. 启动扫地机器人
    """.trimIndent()
}

// 回家场景：用户说"我回来了"、"到家了"时加载
class ComeHomeSkill : Skill {
    override val name: String = "come_home"
    override val description: String = "用户说我回来了、到家了时触发"

    override val standalone: Boolean = true

    override suspend fun load(): String = """
        # 回家场景

        加载工具集 home_control
        1. 打开玄关灯
        2. 打开窗帘
        3. 空调调至24°C
    """.trimIndent()
}
