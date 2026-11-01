package com.helix.app.audit

import com.helix.app.application.diagnostics.DiagnosticRedaction
import com.helix.app.proot.CommandDetailState
import com.helix.app.proot.CommandResultBrowser
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class CommandOutputCursor(
    val time: Long,
    val id: String,
)

data class CommandOutputMatch(
    val turnId: String,
    val callId: String,
    val excerpt: String,
)

data class CommandOutputPage(
    val matches: List<CommandOutputMatch>,
    val next: CommandOutputCursor?,
    val scanned: Int,
    val limited: Boolean,
)

/** Search only persisted, verified previews. Never starts or acknowledges a command. */
internal object CommandOutputSearch {
    suspend fun find(
        storage: HelixStorage,
        query: String,
        cursor: CommandOutputCursor?,
    ): CommandOutputPage {
        require(query.isNotBlank() && query.length <= 256)
        val rows = storage.toolCalls.commandHistoryPage(cursor?.time ?: Long.MAX_VALUE, cursor?.id ?: "\uffff")
        val matches = mutableListOf<CommandOutputMatch>()
        var limited = false
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            val result = storage.toolResults.byToolCall(row.callId)
            val size =
                result?.contentRef?.let {
                    com.helix.core.storage.content.ContentRef
                        .parse(it)
                        .size
                } ?: 0L
            val verified = result?.verified == true && size <= 4 * 1024 * 1024
            val view = if (verified) CommandResultBrowser.browseSync(storage, row.turnId, row.callId) else null
            if (view == null) {
                limited = true
                continue
            }
            val full = view.stdout + "\n" + view.stderr
            limited = limited || incomplete(view, full.length)
            excerpt(full.take(MAX_CHARS), query)?.let { matches += CommandOutputMatch(row.turnId, row.callId, it) }
        }
        val next = rows.lastOrNull()?.takeIf { rows.size == 50 }?.let { CommandOutputCursor(it.createdAt, it.id) }
        return CommandOutputPage(matches, next, rows.size, limited)
    }

    private fun incomplete(
        view: com.helix.app.proot.CommandResultView,
        size: Int,
    ): Boolean =
        view.truncated || size > MAX_CHARS || view.state in
            setOf(
                CommandDetailState.READ_FAILED,
                CommandDetailState.EVIDENCE_EXPIRED,
                CommandDetailState.UNKNOWN,
                CommandDetailState.RUNNING,
                CommandDetailState.SUBMITTED,
            )

    internal fun excerpt(
        text: String,
        query: String,
    ): String? {
        val safe = DiagnosticRedaction.redact(text)
        val offset = safe.indexOf(query, ignoreCase = true)
        return if (offset <
            0
        ) {
            null
        } else {
            safe.substring((offset - 96).coerceAtLeast(0), (offset + 384).coerceAtMost(safe.length))
        }
    }

    private const val MAX_CHARS = 2_000_000
}
