package io.github.yeyi.agent.demo.s2s

import io.github.yeyi.agent.realtime.Tool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MusicControlTool : Tool {
    override val name: String = "control_music"
    override val description: String =
        "用户要求控制音乐播放时调用。通过 action 参数指定具体操作:play=开始/恢复播放,pause=暂停,next=下一首,previous=上一首。"

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", "string")
                put("description", "要执行的音乐控制动作")
                put("enum", buildJsonArray {
                    add("play")
                    add("pause")
                    add("next")
                    add("previous")
                })
            })
        })
        put("required", buildJsonArray { add("action") })
    }

    override suspend fun execute(arguments: JsonElement): String {
        val action = (arguments as? JsonObject)
            ?.get("action")
            ?.jsonPrimitive
            ?.content
            ?: return "未知动作"
        return when (action) {
            "play" -> "已开始播放 陈奕迅"
            "pause" -> "已暂停播放 陈奕迅"
            "next" -> "已切换到下一首 张学友"
            "previous" -> "已切换到上一首 陈奕迅"
            else -> "未知动作: $action"
        }
    }
}