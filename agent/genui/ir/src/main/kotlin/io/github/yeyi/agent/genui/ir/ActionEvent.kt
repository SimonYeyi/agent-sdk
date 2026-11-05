package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 卡片动作回流事件：用户点击卡片动作后由渲染引擎构造，demo 组装为新一轮 AgentQuery 触发推理。
 *
 * @param instanceId 来源卡片锚点，与 [CardIr.instanceId] 一致
 * @param actionId 被点击的动作 id，命中 [CardIr.actions] 白名单
 * @param payload 被点击节点的绑定数据，工具作者决定承载哪些回流上下文
 */
@Serializable
public data class ActionEvent(
    public val instanceId: String,
    public val actionId: String,
    public val payload: JsonObject = JsonObject(emptyMap()),
)
