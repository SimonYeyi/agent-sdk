package io.github.yeyi.agent.app.demo.tools

import io.github.yeyi.agent.tool.compression.CompressTool
import io.github.yeyi.agent.tool.serialization.tool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 获取当前位置（固定返回珠海）
 */
val getLocationTool = CompressTool(
    tool("get_location", "获取当前位置") { _, _ ->
        val location = buildJsonObject {
            put("city", JsonPrimitive("珠海"))
            put("province", JsonPrimitive("广东"))
            put("country", JsonPrimitive("中国"))
            put("latitude", JsonPrimitive(22.2769))
            put("longitude", JsonPrimitive(113.5678))
        }
        location.toString()
    }
)
