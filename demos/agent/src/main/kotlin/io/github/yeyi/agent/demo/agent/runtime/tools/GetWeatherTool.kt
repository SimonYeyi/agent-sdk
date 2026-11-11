package io.github.yeyi.agent.demo.agent.runtime.tools

import io.github.yeyi.agent.genui.ir.Action
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.genui.tool.UiTool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.compression.CompressTool
import io.github.yeyi.agent.tool.serialization.Description
import io.github.yeyi.agent.tool.serialization.SchemaGenerator
import io.github.yeyi.agent.tool.serialization.tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

/** 查询指定城市指定时间的天气数据（共享数据源，产卡/纯文本两个工具复用）。 */
internal fun weatherData(city: String, time: WeatherTime): JsonObject {
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
    }

    return buildJsonObject {
        put("city", JsonPrimitive(city))
        put("time", JsonPrimitive(time.name.lowercase()))
        put("temperature", JsonPrimitive(weatherData["temperature"].toString()))
        put("condition", JsonPrimitive(weatherData["condition"].toString()))
        put("humidity", JsonPrimitive(weatherData["humidity"].toString()))
        put("wind_speed", JsonPrimitive(weatherData["wind_speed"].toString()))
        put("description", JsonPrimitive(weatherData["description"].toString()))
    }
}

/**
 * 查询指定城市的天气（支持时间参数）——纯文本回执版，供子 agent 等场景使用。
 */
val getWeatherTool = CompressTool(
    tool<GetWeatherRequest, JsonElement>(
        "get_weather",
        "当需要查询指定地点的天气情况时使用"
    ) { params, _ ->
        weatherData(params.city, params.time ?: WeatherTime.NOW)
    }
)

/**
 * 天气卡片产卡工具：查询天气并对外发射 iOS 风格天气卡。
 *
 * 与 [getWeatherTool] 同名同数据源，但继承 [UiTool] 产卡：
 * 卡片内嵌工具结果 metadata，随事件流实时送达渲染、随记忆链路持久化恢复；
 * 卡片内嵌时间切换按钮（现在/明天/后天），点击经 ActionEvent 回流触发新一轮推理。
 */
class WeatherCardTool : UiTool() {

    override val name: String = "get_weather"
    override val description: String = "当需要查询指定地点的天气情况时使用"

    override val toolSchema: ToolParameters =
        ToolParameters.JsonSchema(SchemaGenerator.generateSchema(GetWeatherRequest.serializer()))

    override suspend fun getResult(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val params = Json.decodeFromJsonElement(GetWeatherRequest.serializer(), arguments)
        return weatherData(params.city, params.time ?: WeatherTime.NOW)
    }

    override suspend fun buildCard(
        arguments: JsonElement,
        result: JsonElement?,
        context: ToolExecutionContext
    ): CardIr? {
        val obj = result as? JsonObject ?: return null
        val city = obj["city"]?.jsonPrimitive?.contentOrNull ?: return null
        val temperature = obj["temperature"]?.jsonPrimitive?.contentOrNull ?: return null
        val condition = obj["condition"]?.jsonPrimitive?.contentOrNull ?: return null
        val humidity = obj["humidity"]?.jsonPrimitive?.contentOrNull ?: return null
        val wind = obj["wind_speed"]?.jsonPrimitive?.contentOrNull ?: return null
        val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: return null
        val time = obj["time"]?.jsonPrimitive?.contentOrNull ?: "now"

        val timeLabel = when (time) {
            "today" -> "今天"
            "tomorrow" -> "明天"
            "day_after_tomorrow" -> "后天"
            else -> "现在"
        }

        fun text(content: String, vararg extra: Pair<String, String>): TreeNode {
            val bindings = buildJsonObject {
                put("content", JsonPrimitive(content))
                extra.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            }
            return TreeNode(component = "Text", bindings = bindings)
        }

        fun button(action: Action, variant: String): TreeNode = TreeNode(
            component = "Button",
            bindings = buildJsonObject { put("variant", JsonPrimitive(variant)) },
            action = action,
        )

        return CardIr(
            instanceId = context.toolCallId,
            root = TreeNode(
                component = "Card",
                bindings = buildJsonObject {
                    put("spacing", JsonPrimitive(12))
                    put("padding", JsonPrimitive(16))
                },
                children = listOf(
                    // 城市 + 时间 | 大号温度
                    TreeNode(
                        component = "Row",
                        bindings = buildJsonObject {
                            put("spacing", JsonPrimitive(8))
                            put("arrangement", JsonPrimitive("spaceBetween"))
                            put("verticalAlignment", JsonPrimitive("center"))
                        },
                        children = listOf(
                            TreeNode(
                                component = "Column",
                                bindings = buildJsonObject { put("spacing", JsonPrimitive(2)) },
                                children = listOf(
                                    text(city, "style" to "title2", "weight" to "semibold"),
                                    text(timeLabel, "style" to "caption", "color" to "secondary"),
                                ),
                            ),
                            text(
                                "$temperature°",
                                "style" to "largeTitle",
                                "weight" to "bold",
                                "color" to "primary"
                            ),
                        ),
                    ),
                    // 天气标签行
                    TreeNode(
                        component = "Row",
                        bindings = buildJsonObject { put("spacing", JsonPrimitive(8)) },
                        children = listOf(
                            TreeNode(
                                component = "Badge",
                                bindings = buildJsonObject {
                                    put(
                                        "content",
                                        JsonPrimitive(condition)
                                    )
                                },
                            ),
                            TreeNode(
                                component = "Badge",
                                bindings = buildJsonObject {
                                    put(
                                        "content",
                                        JsonPrimitive("湿度 $humidity%")
                                    )
                                },
                            ),
                            TreeNode(
                                component = "Badge",
                                bindings = buildJsonObject {
                                    put(
                                        "content",
                                        JsonPrimitive("风速 $wind km/h")
                                    )
                                },
                            ),
                        ),
                    ),
                    TreeNode(component = "Divider", bindings = emptyMap()),
                    text(description, "style" to "footnote", "color" to "secondary"),
                    // 时间切换按钮：点击经 ActionEvent 回流触发新一轮推理
                    TreeNode(
                        component = "Row",
                        bindings = buildJsonObject { put("spacing", JsonPrimitive(8)) },
                        children = listOf(
                            button(Action("查看现在天气", payload = city), "primary"),
                            button(Action("查看明天天气", payload = city), "secondary"),
                            button(Action("查看后天天气", payload = city), "secondary"),
                        ),
                    ),
                ),
            ),
        )
    }
}
