package app.hermes.mobile.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HermesServerStatus(
    val status: String = "ok",
    @SerialName("auth_required")
    val authRequired: Boolean = false,
    @SerialName("auth_providers")
    val authProviders: List<String> = emptyList(),
    @SerialName("auth_flows")
    val authFlows: List<String> = emptyList(),
    val version: String? = null
)
