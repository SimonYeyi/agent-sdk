package io.github.yeyi.agent.demo.team.runtime

import androidx.a2ui.model.catalog.functions.A2uiLocaleProvider
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import io.github.yeyi.agent.genui.adapter.UiRenderAdapter
import io.github.yeyi.agent.genui.adapter.a2ui.A2uiRenderAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * team demo 全局运行时装配点：A2UI 渲染后端。
 *
 * 与 demos/agent 的 [io.github.yeyi.agent.demo.agent.runtime.AgentRuntime] 平级但互不污染：
 * team 侧只接 [A2uiRenderAdapter]（官方 androidx.a2ui 引擎 + material3-a2ui Compose 渲染），
 * 不引入 demos/agent 的 default adapter。
 *
 * 卡片不再走独立旁路通道：产卡工具把卡片内嵌进工具结果 metadata，
 * 随事件流实时送达渲染、随记忆链路持久化恢复（与 demos/agent 的装配口径一致）。
 *
 * 渲染后端 [cardAdapter] 是 app 级单例：引擎消息循环与动作回流收集挂在
 * [CoroutineScope(SupervisorJob() + Dispatchers.Main)] 上，生命周期随 app；
 * 卡片 surface 按 instanceId 幂等投影，多会话/多次重建 agent 共享同一后端。
 */
object TeamRuntime {

    /**
     * 卡片渲染后端：A2UI v0.9 协议 + 官方引擎 + material3-a2ui Compose 渲染入口。
     *
     * 目录用官方 Material A2uiBasicCatalogV1（image/video/audioPlayer 以空实现注入——
     * 本 demo 卡片只用 Text/Row/Column/Card/Divider/Button 基础组件，媒体组件不落地渲染）。
     */
    val cardAdapter: UiRenderAdapter = A2uiRenderAdapter(
        catalog = materialA2uiBasicCatalogV1(
            image = MaterialA2uiBasicCatalogV1Defaults.image { _, _, _, _, _ -> },
            video = MaterialA2uiBasicCatalogV1Defaults.video { _, _, _ -> },
            audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer { _, _, _, _ -> },
            urlOpener = {},
            messageFormatter = { pattern, _, _ -> pattern },
            localeProvider = A2uiLocaleProvider.Default,
        ),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    )
}
