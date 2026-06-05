package io.github.yeyi.agent.app.demo

import io.github.yeyi.agent.app.demo.tools.CalculatorTool
import io.github.yeyi.agent.app.demo.tools.GetCurrentTimeTool
import io.github.yeyi.agent.app.demo.tools.WebSearchMockTool
import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.providers.openai.OpenAiClient

/**
 * 构造一个配好演示 Tool 与默认 LLM Client 的 Agent。
 *
 * **API Key 来源**: 生产场景应通过 ViewModel 注入;Demo 中从环境变量读
 * (`OPENAI_API_KEY`)。可后续替换为 BuildConfig 字段。
 */
object DemoAgentFactory {

    fun create(): Agent {
        val apiKey = System.getenv("OPENAI_API_KEY")
            ?: error("OPENAI_API_KEY environment variable not set. Demo only — replace with BuildConfig in production.")
        val client = OpenAiClient(
            apiKey = apiKey,
            model = "gpt-4o-mini",
        )
        return agent {
            systemPrompt = "你是一个 helpful 助手。优先使用工具完成任务。"
            llmClient = client
            tool(GetCurrentTimeTool())
            tool(CalculatorTool())
            tool(WebSearchMockTool())
            maxIterations = 8
        }
    }
}
