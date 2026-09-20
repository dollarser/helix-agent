package com.helix.core.storage.export

/** Closed projection: no configuration, Secret, approval binding/proof or filesystem path columns. */
internal data class SessionExportQuery(
    val type: SessionExportType,
    val from: String,
    val predicate: String,
    val fields: List<String>,
    val order: String = "x.id",
    val identityPrefix: String = type.wireName,
) {
    val sortColumns: List<String> = order.split(',').map { it.trim().substringAfter("x.") }

    fun keysSql(after: Boolean): String {
        val keys = (listOf(fields.first()) + sortColumns).distinct().joinToString(",") { "x.$it" }
        val continuation = if (after) " AND ($order) > (${sortColumns.joinToString(",") { "?" }})" else ""
        return "SELECT $keys FROM $from WHERE ($predicate)$continuation ORDER BY $order LIMIT $PAGE_ROWS"
    }

    fun rowSql(): String = "SELECT ${values()} FROM $from WHERE x.${fields.first()} = ?"

    private fun values(): String =
        fields.joinToString(",") { name ->
            "CASE WHEN length(CAST(x.$name AS BLOB)) <= $CELL_BYTES THEN x.$name ELSE NULL END AS $name," +
                "length(CAST(x.$name AS BLOB)) AS ${name}_bytes"
        }

    companion object {
        const val CELL_BYTES = 128 * 1024
        const val PAGE_ROWS = 128
        private const val TURN_SCOPE = "SELECT id FROM turns WHERE sessionId = ?"
        private const val TOOL_SCOPE = "SELECT id FROM tool_calls WHERE turnId IN ($TURN_SCOPE)"
        private const val MODEL_SCOPE = "SELECT id FROM model_calls WHERE turnId IN ($TURN_SCOPE)"

        val ALL =
            listOf(
                SessionExportQuery(
                    SessionExportType.SESSION,
                    "sessions x",
                    "x.id = ?",
                    listOf("id", "title", "providerId", "modelId", "createdAt", "archivedAt"),
                ),
                SessionExportQuery(
                    SessionExportType.TURN,
                    "turns x",
                    "x.sessionId = ?",
                    listOf("id", "sessionId", "state", "stepCount", "startedAt", "endedAt", "errorCode"),
                    "x.startedAt, x.id",
                ),
                SessionExportQuery(
                    SessionExportType.MESSAGE,
                    "messages x",
                    "x.sessionId = ?",
                    listOf("id", "sessionId", "turnId", "role", "kind", "contentRef", "sequence"),
                    "x.sequence, x.id",
                ),
                SessionExportQuery(
                    SessionExportType.MODEL_CALL,
                    "model_calls x",
                    "x.turnId IN ($TURN_SCOPE)",
                    listOf(
                        "id",
                        "turnId",
                        "providerSnapshot",
                        "state",
                        "usage",
                        "requestId",
                        "promptFingerprint",
                        "promptSections",
                    ),
                ),
                SessionExportQuery(
                    SessionExportType.TOOL_CALL,
                    "tool_calls x",
                    "x.turnId IN ($TURN_SCOPE)",
                    listOf("id", "turnId", "callId", "name", "version", "argsJson", "argsHash", "state"),
                ),
                SessionExportQuery(
                    SessionExportType.TOOL_RESULT,
                    "tool_results x",
                    "x.toolCallId IN ($TOOL_SCOPE)",
                    listOf("id", "toolCallId", "status", "summary", "contentRef", "verified"),
                ),
                SessionExportQuery(
                    SessionExportType.EXECUTION,
                    "executions x",
                    "x.toolCallId IN ($TOOL_SCOPE)",
                    listOf("id", "toolCallId", "runtime", "limitsJson", "exitCode", "signal"),
                ),
                SessionExportQuery(
                    SessionExportType.APPROVAL,
                    "approvals x",
                    "x.toolCallId IN ($TOOL_SCOPE)",
                    listOf("id", "toolCallId", "decision", "decidedAt", "consumedAt", "expiresAt"),
                ),
                SessionExportQuery(
                    SessionExportType.ARTIFACT,
                    "artifacts x",
                    "x.sessionId = ?",
                    listOf("id", "sessionId", "mediaType", "size", "sha256", "turnId"),
                ),
                SessionExportQuery(
                    SessionExportType.ATTACHMENT,
                    "message_attachments x",
                    "x.messageId IN (SELECT id FROM messages WHERE sessionId = ?)",
                    listOf("rowId", "messageId", "artifactId", "ordinal", "purpose", "boundSha256"),
                    "x.messageId, x.ordinal, x.rowId",
                ),
                SessionExportQuery(
                    SessionExportType.GOAL_RUN,
                    "goal_runs x",
                    "x.id IN (SELECT runId FROM goal_turn_bindings WHERE turnId IN ($TURN_SCOPE))",
                    listOf(
                        "id",
                        "goalId",
                        "wakeReason",
                        "outcome",
                        "startedAt",
                        "endedAt",
                        "wakeDurationMillis",
                        "modelCalls",
                        "toolCalls",
                        "tokens",
                    ),
                    "x.startedAt, x.id",
                ),
                SessionExportQuery(
                    SessionExportType.GOAL_BINDING,
                    "goal_turn_bindings x",
                    "x.turnId IN ($TURN_SCOPE)",
                    listOf("turnId", "runId"),
                    "x.turnId",
                ),
                SessionExportQuery(
                    SessionExportType.USAGE,
                    "goals x",
                    "x.id IN (SELECT goalId FROM goal_runs WHERE id IN " +
                        "(SELECT runId FROM goal_turn_bindings WHERE turnId IN ($TURN_SCOPE)))",
                    listOf(
                        "id",
                        "state",
                        "budgets",
                        "runCount",
                        "modelCalls",
                        "toolCalls",
                        "totalTokens",
                        "runTimeMillis",
                        "finishReason",
                    ),
                    identityPrefix = "usage:goal",
                ),
                SessionExportQuery(
                    SessionExportType.USAGE,
                    "audit_events x",
                    "x.correlationId IN ($MODEL_SCOPE) AND x.type IN " +
                        "('budget.request','budget.admitted','budget.result','context.compaction')",
                    listOf("id", "correlationId", "type", "redactedPayload", "timestamp"),
                    "x.timestamp, x.id",
                    "usage:audit",
                ),
            )
    }
}
