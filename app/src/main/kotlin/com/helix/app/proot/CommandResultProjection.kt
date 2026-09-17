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
    fun project(input: CommandResultInput): CommandResultView =
        with(input) {
            if (browse.archiveReadFailed) {
                return@with CommandResultView(
                    callId,
                    toolName,
                    commandTextFromArgs(argsJson),
                    callState,
                    turnState,
                    sessionId,
                    scopeLabel,
                    browse.binding,
                    CommandDetailState.READ_FAILED,
                    null,
                    null,
                    "",
                    "",
                    false,
                    emptyList(),
                    null,
                    false,
                )
            }
            val content = resultContent?.let(::parseContent)
            val streams = browse.archive
            val stdout = streams?.stdout ?: content?.stdout.orEmpty()
            val stderr = streams?.stderr ?: content?.stderr.orEmpty()
            val files = streams?.files.orEmpty()
            val truncated = (streams?.truncated ?: false)
            val acknowledged = streams?.acknowledged

            val state = resultState(callState, turnState, resultStatus, resultSummary, content?.state)
            val exitCode = exitCode(state, content, resultSummary)
            val noOutput =
                state != CommandDetailState.RUNNING &&
                    stdout.isBlank() && stderr.isBlank() && files.isEmpty()
            return@with CommandResultView(
                callId,
                toolName,
                commandTextFromArgs(argsJson),
                callState,
                turnState,
                sessionId,
                scopeLabel,
                browse.binding,
                state,
                exitCode,
                resultSummary.takeIf { state in DETAIL_STATES },
                stdout,
                stderr,
                truncated,
                files,
                acknowledged,
                noOutput,
            )
        }

    private fun exitCode(
        state: CommandDetailState,
        content: ContentStreams?,
        summary: String?,
    ): Int? =
        when (state) {
            CommandDetailState.SUCCEEDED -> {
                content?.exitCode
            }

            CommandDetailState.FAILED -> {
                summary
                    ?.let { EXIT_CODE_IN_TEXT.find(it) }
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }

            else -> {
                null
            }
        }

    private fun resultState(
        callState: String,
        turnState: String,
        resultStatus: String?,
        resultSummary: String?,
        contentState: String?,
    ): CommandDetailState =
        when {
            !isSettled(callState) && !isTerminalTurn(turnState) -> {
                CommandDetailState.RUNNING
            }

            callState == "COMPLETED" -> {
                if (contentState ==
                    "SUCCEEDED"
                ) {
                    CommandDetailState.SUCCEEDED
                } else {
                    CommandDetailState.UNKNOWN
                }
            }

            callState == "CANCELLED" -> {
                CommandDetailState.CANCELLED
            }

            callState == "DENIED" -> {
                CommandDetailState.DENIED
            }

            resultStatus == null -> {
                CommandDetailState.UNKNOWN
            }

            else -> {
                failureState(resultSummary.orEmpty())
            }
        }

    private fun failureState(summary: String): CommandDetailState =
        when {
            "evidence expired" in summary -> CommandDetailState.EVIDENCE_EXPIRED
            "no longer knows" in summary || "result is unknown" in summary -> CommandDetailState.UNKNOWN
            else -> CommandDetailState.FAILED
        }

    private val DETAIL_STATES =
        setOf(
            CommandDetailState.FAILED,
            CommandDetailState.UNKNOWN,
            CommandDetailState.EVIDENCE_EXPIRED,
            CommandDetailState.CANCELLED,
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
        val obj = runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull() ?: return argsJson
        val commands = (obj["command"] as? JsonArray)?.takeIf { it.isNotEmpty() }
        return commands?.joinToString("\n") { (it as? JsonPrimitive)?.content.orEmpty() }
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

/** Persisted call facts used by the read-only projection. */
data class CommandResultInput(
    val callId: String,
    val toolName: String,
    val argsJson: String,
    val callState: String,
    val turnState: String,
    val sessionId: String,
    val resultStatus: String?,
    val resultSummary: String?,
    val resultContent: String?,
    val browse: CommandBrowseFacts,
    val scopeLabel: String,
)
