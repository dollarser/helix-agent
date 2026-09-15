package com.helix.app.approval

import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.storage.entity.SessionEntity

/** Preference context only: never a filesystem grant or an approval proof. */
data class PreferenceScopeChoice(
    val scope: ToolApprovalPreferenceScope,
    val ref: String,
    val label: String,
    val sessionId: String? = null,
    val workspaceRef: String? = null,
) {
    val key: String get() = "${scope.name}:$ref"

    companion object {
        val GLOBAL = PreferenceScopeChoice(ToolApprovalPreferenceScope.GLOBAL, "", "")
    }
}

fun preferenceScopeChoices(
    sessions: List<SessionEntity>,
    defaultWorkspace: String,
): List<PreferenceScopeChoice> =
    listOf(PreferenceScopeChoice.GLOBAL) +
        sessions.filter { it.archivedAt == null }.map {
            PreferenceScopeChoice(
                ToolApprovalPreferenceScope.SESSION,
                it.id,
                it.title,
                it.id,
                it.directoryRef ?: defaultWorkspace,
            )
        } +
        (listOf(defaultWorkspace) + sessions.mapNotNull { it.directoryRef }).distinct().map {
            PreferenceScopeChoice(
                ToolApprovalPreferenceScope.WORKSPACE,
                it,
                if (it ==
                    defaultWorkspace
                ) {
                    ""
                } else {
                    it
                },
                workspaceRef = it,
            )
        }

class PreferenceContractChanged : IllegalStateException("review the changed tool contract before allowing it")

internal fun PreferenceScopeChoice.requireAvailable(choices: List<PreferenceScopeChoice>) {
    require(this in choices) { "selected scope is no longer available" }
}
