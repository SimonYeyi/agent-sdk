package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
public data class Mention(
    val userId: String,
    val userName: String? = null,
    val key: String? = null
)
