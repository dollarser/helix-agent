package com.helix.app.chat

import com.helix.app.R

/** A receipt describes the committed selection, not merely admission to an asynchronous queue. */
enum class SessionModelSelectionResult(
    val messageRes: Int,
) {
    APPLIED(R.string.model_selection_applied),
    SESSION_CHANGED(R.string.model_selection_session_changed),
    BUSY(R.string.model_selection_busy),
    PROVIDER_UNAVAILABLE(R.string.model_selection_provider_unavailable),
    MODEL_UNAVAILABLE(R.string.model_selection_model_unavailable),
    SETTINGS_CHANGED(R.string.model_selection_settings_changed),
    FAILED(R.string.model_selection_failed),
}

/** Compare only the fields edited by the model picker; retain unrelated current session settings. */
data class SessionModelSelectionSnapshot(
    val providerId: String?,
    val modelId: String?,
    val reasoning: com.helix.core.model.ReasoningEffort,
)

internal fun selectionRejection(
    expectedSession: String?,
    currentSession: String?,
    busy: Boolean,
    providerReady: Boolean,
    modelSelectable: Boolean,
): SessionModelSelectionResult? =
    when {
        expectedSession == null || expectedSession != currentSession -> SessionModelSelectionResult.SESSION_CHANGED
        busy -> SessionModelSelectionResult.BUSY
        !providerReady -> SessionModelSelectionResult.PROVIDER_UNAVAILABLE
        !modelSelectable -> SessionModelSelectionResult.MODEL_UNAVAILABLE
        else -> null
    }
