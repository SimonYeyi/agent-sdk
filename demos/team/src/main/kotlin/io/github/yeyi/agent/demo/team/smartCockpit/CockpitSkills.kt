package io.github.yeyi.agent.demo.team.smartCockpit

import io.github.yeyi.agent.skill.Skill

/**
 * Cockpit Skills - 场景指导文档。
 */

// 驾驶模式：用户说"开车了"、"出发了"时触发
class DrivingModeSkill : Skill {
    override val name: String = "driving_mode"
    override val description: String = "用户说开车了、出发了时触发"

    override suspend fun load(): String = """
        # 驾驶模式

        加载工具集 driving_assist, comfort_control, cabin_environment
        1. 导航设置
        2. 空调调整
        3. 关闭车窗
    """.trimIndent()
}

// 休息模式：用户说"休息一下"、"小憩一下"时触发
class RestModeSkill : Skill {
    override val name: String = "rest_mode"
    override val description: String = "用户说休息一下、小憩一下时触发"

    override suspend fun load(): String = """
        # 休息模式

        加载工具集 comfort_control, cabin_environment
        1. 空调调至24°C
        2. 座椅放倒
        3. 氛围灯调暗
    """.trimIndent()
}

// 观影模式：用户说"看电影"、"影院模式"时触发
class MovieModeSkill : Skill {
    override val name: String = "movie_mode"
    override val description: String = "用户说看电影、影院模式时触发"

    override suspend fun load(): String = """
        # 观影模式

        加载工具集 cabin_environment, comfort_control
        1. 关闭车窗
        2. 氛围灯调至观影模式
        3. 空调调至舒适温度
    """.trimIndent()
}

// 回家模式：用户说"回家了"、"导航回家"时触发
class GoHomeSkill : Skill {
    override val name: String = "go_home"
    override val description: String = "用户说回家了、导航回家时触发"

    override val standalone: Boolean get() = true

    override suspend fun load(): String = """
        # 回家模式

        加载工具集 driving_assist, cabin_environment
        1. 导航回家
        2. 打开车窗透气
    """.trimIndent()
}
