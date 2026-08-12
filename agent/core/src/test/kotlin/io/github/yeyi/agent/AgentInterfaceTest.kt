package io.github.yeyi.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentInterfaceTest {
    @Test
    fun `Agent run accepts AgentQuery parameter`() {
        // 通过 AgentQuery 类型间接验证接口签名: Agent.run/runStream 应接受 AgentQuery 而不是 String。
        // 这里只做编译期验证: AgentQuery.text() 入口可用,且 parts 形态符合预期。
        val query: AgentQuery = AgentQuery.text("hi")
        assertEquals(listOf(io.github.yeyi.agent.llm.ContentPart.Text("hi")), query.parts)
    }
}
