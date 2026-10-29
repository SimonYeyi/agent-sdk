package io.github.yeyi.agent.demo.realtime.s2s

import io.github.yeyi.agent.realtime.Tool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class GetTimeTool : Tool {
    override val name = "get_time"
    override val description = "获取当前时间"
    override val parametersSchema: JsonObject = JsonObject(emptyMap())
    override suspend fun execute(arguments: JsonElement): String {
        return "当前时间: ${java.time.LocalDateTime.now()}"
    }
}