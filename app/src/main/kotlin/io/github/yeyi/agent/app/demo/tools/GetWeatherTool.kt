package io.github.yeyi.agent.app.demo.tools

import io.github.yeyi.agent.tool.compression.CompressTool
import io.github.yeyi.agent.tool.serialization.Description
import io.github.yeyi.agent.tool.serialization.tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
enum class WeatherTime {
    @Description("现在")
    NOW,
    @Description("今天")
    TODAY,
    @Description("明天")
    TOMORROW,
    @Description("后天")
    DAY_AFTER_TOMORROW
}

@Serializable
data class GetWeatherRequest(
    @Description("城市名称")
    val city: String,
    @Description("查询时间，默认为 NOW")
    val time: WeatherTime? = null
)

/**
 * 查询指定城市的天气（支持时间参数）
 */
val getWeatherTool = CompressTool(
    tool<GetWeatherRequest, JsonElement>("get_weather", "当需要查询指定地点的天气情况时使用") { params, _ ->
        val city = params.city
        val time = params.time ?: WeatherTime.NOW

        val weatherData = when (time) {
            WeatherTime.TODAY, WeatherTime.NOW -> mapOf(
                "temperature" to 34,
                "condition" to "炎热",
                "humidity" to 35,
                "wind_speed" to 12,
                "description" to "天气炎热，不适宜出行"
            )

            WeatherTime.TOMORROW -> mapOf(
                "temperature" to 28,
                "condition" to "多云",
                "humidity" to 55,
                "wind_speed" to 8,
                "description" to "明天多云，温度适宜"
            )

            WeatherTime.DAY_AFTER_TOMORROW -> mapOf(
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
                "description" to "无效的时间参数"
            )
        }

        buildJsonObject {
            put("city", JsonPrimitive(city))
            put("time", JsonPrimitive(time.name.lowercase()))
            put("temperature", JsonPrimitive(weatherData["temperature"].toString()))
            put("condition", JsonPrimitive(weatherData["condition"].toString()))
            put("humidity", JsonPrimitive(weatherData["humidity"].toString()))
            put("wind_speed", JsonPrimitive(weatherData["wind_speed"].toString()))
            put("description", JsonPrimitive(weatherData["description"].toString()))
        }
    }
)
