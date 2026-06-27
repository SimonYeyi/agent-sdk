package io.github.yeyi.agent.app.demo.tools

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 查询指定城市的天气（支持时间参数）
 */
class GetWeatherTool : Tool {
    override val name: String = "get_weather"
    override val description: String = "当需要查询指定地点的天气情况时使用"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        schema = """
            {
                "type": "object",
                "properties": {
                    "city": {
                        "type": "string",
                        "description": "城市名称"
                    },
                    "time": {
                        "type": "string",
                        "enum": ["now", "today", "tomorrow", "day_after_tomorrow"],
                        "description": "查询时间，默认为 now"
                    }
                },
                "required": ["city"]
            }
        """.trimIndent()
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val city = arguments.jsonObject["city"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Arguments error: $arguments")
        val time = arguments.jsonObject["time"]?.jsonPrimitive?.content ?: "now"

        // 根据时间返回不同天气
        val weatherData = when (time.lowercase()) {
            "today", "now" -> mapOf(
                "temperature" to 34,
                "condition" to "炎热",
                "humidity" to 35,
                "wind_speed" to 12,
                "description" to "天气炎热，不适宜出行"
            )

            "tomorrow" -> mapOf(
                "temperature" to 28,
                "condition" to "多云",
                "humidity" to 55,
                "wind_speed" to 8,
                "description" to "明天多云，温度适宜"
            )

            "day_after_tomorrow" -> mapOf(
                "temperature" to 26,
                "condition" to "雷雨",
                "humidity" to 35,
                "wind_speed" to 12,
                "description" to "雷雨交加，谨慎出行"
            )

            else -> mapOf(
                "temperature" to -999,
                "condition" to "未知",
                "humidity" to -1,
                "wind_speed" to -1,
                "description" to "无效的时间参数，请使用 now/today/tomorrow/day_after_tomorrow"
            )
        }

        val weather = buildJsonObject {
            put("city", JsonPrimitive(city))
            put("time", JsonPrimitive(time))
            put("temperature", JsonPrimitive(weatherData["temperature"].toString()))
            put("condition", JsonPrimitive(weatherData["condition"].toString()))
            put("humidity", JsonPrimitive(weatherData["humidity"].toString()))
            put("wind_speed", JsonPrimitive(weatherData["wind_speed"].toString()))
            put("description", JsonPrimitive(weatherData["description"].toString()))
        }
        return ToolExecutionResult(content = weather.toString())
    }
}
