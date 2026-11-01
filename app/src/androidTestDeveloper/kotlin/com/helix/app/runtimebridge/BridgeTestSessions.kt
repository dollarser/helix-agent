package com.helix.app.runtimebridge

import com.helix.app.HelixApplication

/** Recover only these suites' UUID-tagged synthetic sessions after an interrupted fixture. */
internal fun clearBridgeTestSessions(app: HelixApplication) {
    val titles = setOf("Script bridge synthetic fixture", "Synthetic script Linux")
    app.appContainer.storage.sessions
        .list()
        .filter {
            it.title in titles && (it.id.startsWith("script-fixture-") || it.id.startsWith("script-linux-"))
        }.forEach { app.appContainer.storage.deleteSessionPermanently(it.id) }
}

internal fun advanceBridgeTestTurn(
    app: HelixApplication,
    id: String,
) {
    val turns = app.appContainer.storage.turns
    listOf(
        com.helix.core.model.TurnState.BUILDING_CONTEXT,
        com.helix.core.model.TurnState.WAITING_MODEL,
        com.helix.core.model.TurnState.RECEIVING_MODEL,
        com.helix.core.model.TurnState.RUNNING_TOOL,
    ).forEach { next -> turns.updateState(turns.resolve(id), next, 0, null, null) }
}
