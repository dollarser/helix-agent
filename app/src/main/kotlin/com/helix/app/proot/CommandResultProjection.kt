package com.helix.app.proot

import com.helix.core.model.TurnState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * HXA-194: the pure read-only projection from persisted facts to the command details view.
 * Stateless and host-testable: no storage, no Context, no Runtime. The same input always
 * yields the same view — opening the page a second time (rotation, back, re-entry) cannot
 * start, replay or acknowledge anything.
 */
object CommandResultProjection {
    fun project(
        callId: String,
        toolName: String,
        argsJson: String,
        facts: CommandResultFacts,
        browse: CommandBrowseFacts,
        scopeLabel: String,
    ): CommandResultView {
        val content = facts.resultContent?.let(::parseContent)
        val state =
            if (browse.archiveReadFailed) {
                // A persisted record claiming an archive that no longer verifies: the
                // page shows the distinct read-failed state and no streams at all.
                CommandDetailState.READ_FAILED
            } else {
                resolveState(facts, content)
            }
        val streams =
            if (browse.archiveReadFailed) {
                OutputStreams("", "", false, emptyList(), null)
            } else {
                val archive = browse.archive
                OutputStreams(
                    stdout = archive?.stdout ?: content?.stdout.orEmpty(),
                    stderr = archive?.stderr ?: content?.stderr.orEmpty(),
                    truncated = archive?.truncated ?: false,
                    files = archive?.files.orEmpty(),
                    acknowledged = archive?.acknowledged,
                )
            }
        return CommandResultView(
            callId,
            toolName,
            commandTextFromArgs(argsJson),
            facts.callState,
            facts.turnState,
            facts.sessionId,
            scopeLabel,
            browse.binding,
            state,
            resolveExitCode(state, content, facts.resultSummary),
            detailFor(state, facts.resultSummary),
            streams.stdout,
            streams.stderr,
            streams.truncated,
            streams.files,
            streams.acknowledged,
            hasNoVisibleOutput(state, streams),
        )
    }

    /**
     * The settled display state: a call that is still in flight inside a live turn shows
     * status only; otherwise the call state — and, where the result row exists, its
     * contents — decide. The projection never guesses an outcome the persisted facts do
     * not prove.
     */
    private fun resolveState(
        facts: CommandResultFacts,
        content: ContentStreams?,
    ): CommandDetailState =
        when {
            !isSettled(facts.callState) && !isTerminalTurn(facts.turnState) -> {
                CommandDetailState.RUNNING
            }

            facts.callState == "COMPLETED" -> {
                when (content?.state) {
                    "SUCCEEDED" -> CommandDetailState.SUCCEEDED

                    // A completed call whose result content is missing or not a
                    // succeeded record: the persisted facts do not prove the outcome.
                    else -> CommandDetailState.UNKNOWN
                }
            }

            facts.callState == "CANCELLED" -> {
                CommandDetailState.CANCELLED
            }

            facts.callState == "DENIED" -> {
                CommandDetailState.DENIED
            }

            facts.resultStatus == null -> {
                CommandDetailState.UNKNOWN
            }

            // FAILED / NEEDS_REVIEW / INTERRUPTED: the persisted detail line
            // distinguishes the expired-evidence and unknown-outcome findings.
            else -> {
                summarizeOutcome(facts.resultSummary)
            }
        }

    private fun summarizeOutcome(summary: String?): CommandDetailState =
        when {
            summary != null && summary.contains("evidence expired") -> {
                CommandDetailState.EVIDENCE_EXPIRED
            }

            summary != null &&
                (
                    summary.contains("no longer knows") ||
                        summary.contains("result is unknown")
                ) -> {
                CommandDetailState.UNKNOWN
            }

            else -> {
                CommandDetailState.FAILED
            }
        }

    /** SUCCEEDED reads the code from the persisted record; FAILED from its detail line. */
    private fun resolveExitCode(
        state: CommandDetailState,
        content: ContentStreams?,
        summary: String?,
    ): Int? =
        when (state) {
            CommandDetailState.SUCCEEDED -> {
                content?.exitCode
            }

            CommandDetailState.FAILED -> {
                EXIT_CODE_IN_TEXT
                    .find(summary.orEmpty())
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }

            else -> {
                null
            }
        }

    /** Only findings that carry a persisted explanation line show it. */
    private fun detailFor(
        state: CommandDetailState,
        summary: String?,
    ): String? =
        when (state) {
            CommandDetailState.FAILED,
            CommandDetailState.UNKNOWN,
            CommandDetailState.EVIDENCE_EXPIRED,
            CommandDetailState.CANCELLED,
            -> {
                summary
            }

            else -> {
                null
            }
        }

    /**
     * A terminal result whose persisted facts show no streams and no files gets its own
     * "no output" line; a still-running command (output viewable after it ends) and the
     * read-failed state never do.
     */
    private fun hasNoVisibleOutput(
        state: CommandDetailState,
        streams: OutputStreams,
    ): Boolean {
        val terminalState =
            state != CommandDetailState.RUNNING && state != CommandDetailState.READ_FAILED
        val blankStreams = streams.stdout.isBlank() && streams.stderr.isBlank()
        return terminalState && blankStreams && streams.files.isEmpty()
    }

    private data class OutputStreams(
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
        val files: List<ProotRecoveredFile>,
        val acknowledged: Boolean?,
    )

    private data class ContentStreams(
        val state: String?,
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
    )

    private fun parseContent(content: String): ContentStreams? =
        runCatching {
            val obj = Json.parseToJsonElement(content) as? JsonObject ?: return@runCatching null
            ContentStreams(
                state = (obj["state"] as? JsonPrimitive)?.content,
                exitCode = (obj["exitCode"] as? JsonPrimitive)?.longOrNull?.toInt(),
                stdout = (obj["stdout"] as? JsonPrimitive)?.content.orEmpty(),
                stderr = (obj["stderr"] as? JsonPrimitive)?.content.orEmpty(),
            )
        }.getOrNull()

    /**
     * The command as already visible in the request parameters: the `command` array joined
     * by newlines, or the `command`/`script` string, or the raw arguments when neither.
     */
    fun commandTextFromArgs(argsJson: String): String {
        val obj =
            runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }
                .getOrNull()
                ?: return argsJson
        val commandArray = (obj["command"] as? JsonArray)?.takeIf { it.isNotEmpty() }
        return commandArray
            ?.joinToString("\n") { (it as? JsonPrimitive)?.content.orEmpty() }
            ?: (obj["command"] as? JsonPrimitive)?.content
            ?: (obj["script"] as? JsonPrimitive)?.content
            ?: argsJson
    }

    private fun isSettled(callState: String): Boolean =
        runCatching {
            com.helix.core.model.ToolCallState
                .valueOf(callState)
                .isTerminal
        }.getOrDefault(false)

    private fun isTerminalTurn(turnState: String): Boolean =
        runCatching { TurnState.valueOf(turnState).isTerminal }.getOrDefault(true)

    private val EXIT_CODE_IN_TEXT = Regex("exit code (\\d+)")
}
