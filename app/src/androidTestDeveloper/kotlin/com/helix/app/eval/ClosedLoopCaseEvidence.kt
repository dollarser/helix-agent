package com.helix.app.eval

import android.content.Context
import android.os.SystemClock
import com.helix.app.AppContainer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

internal data class ClosedLoopCaseEvidence(
    val session: String,
    val case: String,
    val trial: String,
    val model: String,
    val started: Long,
    val passed: Boolean,
    val failure: String?,
)

internal fun saveClosedLoopEvidence(
    app: Context,
    c: AppContainer,
    snapshot: ClosedLoopCaseEvidence,
) = with(snapshot) {
    val turn =
        c.storage.turns
            .listBySession(session)
            .lastOrNull()
    val calls = turn?.let { c.storage.toolCalls.listByTurn(it.id) }.orEmpty()
    val payload =
        buildJsonObject {
            put("case", case)
            put("trial", trial)
            put("session", session)
            put("model", model)
            put("passed", passed)
            put("failure", failure)
            put("turnState", turn?.state)
            put("elapsedMs", SystemClock.elapsedRealtime() - started)
            put(
                "modelCalls",
                turn?.let {
                    c.storage.modelCalls
                        .listByTurn(it.id)
                        .size
                } ?: 0,
            )
            put("trajectory", evaluationTrajectory(c, session))
            put("turns", evidenceTurns(c, session))
            put("calls", evidenceCalls(c, calls))
            put(
                "response",
                c.storage.messages
                    .listBySession(session)
                    .filter { it.role == "ASSISTANT" && it.kind == "TEXT" }
                    .mapNotNull { c.storage.messages.readContent(it) }
                    .joinToString("\n"),
            )
            val models = turn?.let { c.storage.modelCalls.listByTurn(it.id) }.orEmpty()
            put("usage", JsonArray(models.map { JsonPrimitive(it.usage) }))
        }
    File(app.filesDir, "closed-loop-eval").apply { mkdirs() }.resolve("$trial.json").writeText(payload.toString())
}

private fun evidenceCalls(
    c: com.helix.app.AppContainer,
    calls: List<com.helix.core.storage.entity.ToolCallEntity>,
): JsonArray =
    JsonArray(
        calls.map { call ->
            buildJsonObject {
                put("name", call.name)
                put("state", call.state)
                put("args", call.argsJson)
                val result = c.storage.toolResults.byToolCall(call.callId)
                put("resultStatus", result?.status)
                put("result", result?.let { c.storage.toolResults.readContent(it) })
            }
        },
    )

private fun evidenceTurns(
    c: AppContainer,
    session: String,
): JsonArray =
    JsonArray(
        c.storage.turns.listBySession(session).map { row ->
            buildJsonObject {
                put("id", row.id)
                put("state", row.state)
                put(
                    "modelCalls",
                    c.storage.modelCalls
                        .listByTurn(row.id)
                        .size,
                )
                put(
                    "tools",
                    c.storage.toolCalls
                        .listByTurn(row.id)
                        .size,
                )
            }
        },
    )
