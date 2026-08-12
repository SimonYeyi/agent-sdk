package io.github.yeyi.agent.llm

/** 摘要路径用的紧凑标签: 避免 URL/base64 过长膨胀摘要。 */
internal fun MediaSource.shortLabel(): String = when (this) {
    is MediaSource.Http -> url.take(64)
    is MediaSource.Data -> "$mimeType, ${base64.length / 1024}KB"
    is MediaSource.FileId -> id
}
