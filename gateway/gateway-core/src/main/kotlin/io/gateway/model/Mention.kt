package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
data class Mention(
    val userId: String,
    val userName: String? = null,
    val key: String? = null
)
