package com.helix.app.proot

import com.helix.app.APP_SCOPE_ID
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HXA-194: the read-only command-details browser. Assembles [CommandResultView] from
 * persisted facts ONLY — the turn, the tool call, its settled result row and the flavor
 * seam's browse facts (binding + locally persisted archive preview). Pure reads on the
 * IO dispatcher: opening, re-opening or rotating the details page can never start,
 * replay, submit or acknowledge anything.
 */
internal object CommandResultBrowser {
    suspend fun browse(
        storage: HelixStorage,
        turnId: String,
        callId: String,
    ): CommandResultView? =
        withContext(Dispatchers.IO) {
            browseSync(storage, turnId, callId)
        }

    internal fun browseSync(
        storage: HelixStorage,
        turnId: String,
        callId: String,
    ): CommandResultView? {
        val turn =
            runCatching { storage.turns.resolve(turnId) }.getOrNull()
        val call = turn?.let { storage.toolCalls.byTurnAndCallId(turnId, callId) }
        if (turn == null || call == null) return null
        val scopeLabel =
            runCatching { storage.sessions.resolve(turn.sessionId) }
                .getOrNull()
                ?.let { it.directoryRef ?: APP_SCOPE_ID }
                ?: APP_SCOPE_ID
        val result = storage.toolResults.byToolCall(call.callId)
        val browse =
            if (call.name in COMMAND_TOOL_NAMES) {
                ProotToolModule.browseCommandResult(storage, turnId, callId)
            } else {
                CommandBrowseFacts(null, null, false)
            }
        return CommandResultProjection.project(
            callId = call.callId,
            toolName = call.name,
            argsJson = call.argsJson,
            facts =
                CommandResultFacts(
                    callState = call.state,
                    turnState = turn.state,
                    sessionId = turn.sessionId,
                    resultStatus = result?.status,
                    resultSummary = result?.summary,
                    // A missing body is not a new execution or a UI crash. Independent terminal
                    // receipts/archives remain readable; otherwise the projection says unknown.
                    resultContent = result?.let { runCatching { storage.toolResults.readContent(it) }.getOrNull() },
                ),
            browse = browse,
            scopeLabel = scopeLabel,
        )
    }
}
