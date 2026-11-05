package io.github.yeyi.agent.genui

/**
 * 卡片动作白名单校验器：动作 id 非空且唯一。
 *
 * 白名单/降级校验是 genui 的安全红线，任何渲染后端（自研、A2UI 投影）都必须遵守，
 * 故独立为公开校验器，在协议层收口语义，供所有后端共用。
 */
public class CardIrValidator {

    /**
     * 校验卡片动作白名单。
     *
     * @return 白名单 id 到动作规格的映射；白名单非法（id 为空或重复）时返回 null，
     * 消费方应将整卡降级为纯展示（无动作）。
     */
    public fun validateActions(actions: List<Action>): Map<String, Action>? {
        val byId = HashMap<String, Action>()
        for (action in actions) {
            if (action.id.isBlank()) return null
            if (byId.put(action.id, action) != null) return null
        }
        return byId
    }
}
