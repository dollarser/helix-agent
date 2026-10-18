package com.helix.app.ui

import androidx.compose.runtime.saveable.listSaver
import com.helix.core.model.ReasoningEffort

/** Uncommitted dialog state. Its identity is checked after restore and before rendering or saving. */
internal data class ModelPickerEdit(
    val identity: String,
    val expanded: Boolean,
    val providerId: String?,
    val model: String?,
    val reasoning: ReasoningEffort,
) {
    fun select(
        providerId: String,
        model: String,
    ): ModelPickerEdit =
        if (this.providerId == providerId && this.model == model) {
            this
        } else {
            copy(providerId = providerId, model = model, reasoning = ReasoningEffort.OFF)
        }

    companion object {
        val Saver =
            listSaver<ModelPickerEdit, Any>(
                save = { value ->
                    listOf(
                        value.identity,
                        value.expanded,
                        value.providerId.orEmpty(),
                        value.model.orEmpty(),
                        value.reasoning.name,
                    )
                },
                restore = {
                    ModelPickerEdit(
                        it[0] as String,
                        it[1] as Boolean,
                        (it[2] as String).ifEmpty { null },
                        (it[3] as String).ifEmpty { null },
                        ReasoningEffort.valueOf(it[4] as String),
                    )
                },
            )
    }
}

internal fun modelPickerIdentity(
    session: String?,
    provider: String?,
    model: String?,
    reasoning: ReasoningEffort,
): String =
    listOf(session, provider, model, reasoning.name).joinToString("") { value ->
        value?.let { "${it.length}:$it" } ?: "-1:"
    }
