package com.helix.app.proot

import com.helix.core.model.TurnState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

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
        callState: String,
        turnState: String,
        resultStatus: String?,
        resultSummary: String?,
        resultContent: String?,
        browse: CommandBrowseFacts,
        scopeLabel: String,
    ): CommandResultView {
        if (browse.archiveReadFailed) {
            return CommandResultView(
                callId,
                toolName,
                commandTextFromArgs(argsJson),
                callState,
                turnState,
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

        val state: CommandDetailState =
            when {
                !isSettled(callState) && !isTerminalTurn(turnState) -> CommandDetailState.RUNNING

                callState == "COMPLETED" -> {
                    when (content?.state) {
                        "SUCCEEDED" -> CommandDetailState.SUCCEEDED

                        // A completed call whose result content is missing or not a
                        // succeeded record: the persisted facts do not prove the outcome.
                        else -> CommandDetailState.UNKNOWN
                    }
                }

                callState == "CANCELLED" -> CommandDetailState.CANCELLED
                callState == "DENIED" -> CommandDetailState.DENIED
                resultStatus == null -> CommandDetailState.UNKNOWN

                else -> {
                    // FAILED / NEEDS_REVIEW / INTERRUPTED: the persisted detail line
                    // distinguishes the expired-evidence and unknown-outcome findings.
                    when {
                        resultSummary != null && resultSummary.contains("evidence expired") ->
                            CommandDetailState.EVIDENCE_EXPIRED

                        resultSummary != null &&
                            (resultSummary.contains("no longer knows") ||
                                resultSummary.contains("result is unknown")) ->
                            CommandDetailState.UNKNOWN

                        else -> CommandDetailState.FAILED
                    }
                }
            }
        val exitCode =
            when {
                state == CommandDetailState.SUCCEEDED -> content?.exitCode
                state == CommandDetailState.FAILED ->
                    resultSummary?.let { EXIT_CODE_IN_TEXT.find(it) }?.groupValues?.get(1)?.toIntOrNull()
                else -> null
            }
        val noOutput =
            state != CommandDetailState.RUNNING &&
                stdout.isBlank() && stderr.isBlank() && files.isEmpty()
        return CommandResultView(
            callId,
            toolName,
            commandTextFromArgs(argsJson),
            callState,
            turnState,
            scopeLabel,
            browse.binding,
            state,
            exitCode,
            if (state == CommandDetailState.FAILED || state == CommandDetailState.UNKNOWN ||
                state == CommandDetailState.EVIDENCE_EXPIRED || state == CommandDetailState.CANCELLED
            ) {
                resultSummary
            } else {
                null
            },
            stdout,
            stderr,
            truncated,
            files,
            acknowledged,
            noOutput,
        )
    }

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
        (obj["command"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { commands ->
            return commands.joinToString("\n") { (it as? JsonPrimitive)?.content.orEmpty() }
        }
        return (obj["command"] as? JsonPrimitive)?.content
            ?: (obj["script"] as? JsonPrimitive)?.content
            ?: argsJson
    }

    private fun isSettled(callState: String): Boolean =
        runCatching {
            com.helix.core.model.ToolCallState.valueOf(callState).isTerminal
        }.getOrDefault(false)

    private fun isTerminalTurn(turnState: String): Boolean =
        runCatching { TurnState.valueOf(turnState).isTerminal }.getOrDefault(true)

    private val EXIT_CODE_IN_TEXT = Regex("exit code (\\d+)")
}
