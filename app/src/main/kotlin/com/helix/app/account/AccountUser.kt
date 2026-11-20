package com.helix.app.account

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Account identity is separate from installation-local profile and login generation. */
data class AccountUser(
    val id: String,
    val displayName: String,
    val email: String?,
    val mustChange: Boolean,
    val emailBindingRequired: Boolean,
    val displayNameUpdatedAt: Long? = null,
) {
    internal fun document() =
        buildJsonObject {
            put("id", id)
            put("display_name", displayName)
            displayNameUpdatedAt?.let { put("display_name_updated_at", it) }
            email?.let { put("email", it) }
            put("must_change", mustChange)
            put("email_binding_required", emailBindingRequired)
        }

    companion object {
        fun parse(element: JsonElement?): AccountUser? {
            val value = element as? JsonObject

            fun text(key: String) = (value?.get(key) as? JsonPrimitive)?.takeIf { it.content != "null" }?.content

            fun flag(key: String) = text(key) in setOf("true", "1")
            val id = text("id")?.takeIf { it.isNotBlank() } ?: return null
            return AccountUser(
                id,
                text("display_name").orEmpty(),
                text("email"),
                flag("must_change"),
                flag("email_binding_required"),
                text("display_name_updated_at")?.toLongOrNull(),
            )
        }
    }
}
