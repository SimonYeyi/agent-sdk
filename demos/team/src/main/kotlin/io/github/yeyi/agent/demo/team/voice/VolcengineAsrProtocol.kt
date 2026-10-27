package io.github.yeyi.agent.demo.team.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 火山 Seed-ASR full client request 构造与服务端结果解析（裁剪版）。
 *
 * 迁移自 agentos 项目同文件，仅保留 PTT 按住说话所需的扁平识别模式：
 * 不启用说话人分离 / corpus 上下文等增强能力。
 */
object VolcengineAsrProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    /** 单次服务端响应解析结果：[text] 为最长候选文本，[definite] 为末分句定稿标记。 */
    data class ParsedResult(
        val text: String?,
        val definite: Boolean,
    )

    /**
     * 构造 full client request JSON。
     *
     * 关键参数：
     * - `end_window_size=10000`：VAD 静音容忍 10s，按住期间自然停顿不判停（绕开系统 VAD）
     * - `result_type=full`：全量返回各分句，句中停顿不丢前文
     */
    fun buildFullClientRequestJson(
        sampleRate: Int = 16_000,
        endWindowSizeMs: Int = 10_000,
        uid: String = "agent-demo",
    ): String {
        val root = buildJsonObject {
            putJsonObject("user") {
                put("uid", uid)
            }
            putJsonObject("audio") {
                put("format", "pcm")
                put("rate", sampleRate)
                put("bits", 16)
                put("channel", 1)
            }
            putJsonObject("request") {
                put("model_name", "bigmodel")
                put("enable_itn", true)
                put("enable_punc", true)
                put("enable_nonstream", false)
                put("result_type", "full")
                put("end_window_size", endWindowSizeMs)
                put("show_utterances", true)
            }
        }
        return root.toString()
    }

    /**
     * 解析服务端 JSON 响应。
     *
     * [text] 取 `result.text` 与 `utterances[].text` 中最长者，避免定稿包仅带尾句时丢前文；
     * [definite] 取最后一个分句的 definite 标记。
     */
    fun parseServerResult(payloadJson: String): ParsedResult? {
        val trimmed = payloadJson.trim()
        if (trimmed.isEmpty()) return null
        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        val resultObj = root["result"]?.jsonObject ?: return null
        val fullText = resultObj["text"]?.jsonPrimitive?.content ?: ""
        val utteranceTexts = resultObj["utterances"]?.jsonArray?.mapNotNull { element ->
            element.jsonObject["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        } ?: emptyList()
        val candidates = mutableListOf<String>()
        fullText.trim().takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
        utteranceTexts.forEach { candidates.add(it.trim()) }
        val definite = resultObj["utterances"]?.jsonArray
            ?.lastOrNull()?.jsonObject?.get("definite")?.jsonPrimitive?.content?.trim()
            ?.equals("true", ignoreCase = true) == true
        return ParsedResult(
            text = candidates.maxByOrNull { it.length },
            definite = definite,
        )
    }
}
