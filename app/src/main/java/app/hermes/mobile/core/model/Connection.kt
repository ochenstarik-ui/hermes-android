package app.hermes.mobile.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class HermesConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val allowCleartext: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
