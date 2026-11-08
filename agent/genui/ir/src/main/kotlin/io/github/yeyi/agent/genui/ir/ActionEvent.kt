package io.github.yeyi.agent.genui.ir

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 卡片动作回流事件：用户点击卡片动作后由渲染引擎构造，demo 组装为新一轮 AgentQuery 触发推理。
 *
 * 事件直接携带命中的动作声明 [Action]，意图由工具调用上下文承载，模型结合卡片与历史自然理解，无需专用意图描述字段。
 *
 * @param instanceId 来源卡片锚点，与 [CardIr.instanceId] 一致
 * @param action 被点击的动作的完整声明（id 为程序标识，label 为模型共读文案）
 * @param payload 被点击节点的绑定数据，工具作者决定承载哪些回流上下文（对象参数）
 */
@Serializable
public data class ActionEvent(
    public val instanceId: String,
    public val action: Action,
    public val payload: JsonObject = JsonObject(emptyMap()),
)
