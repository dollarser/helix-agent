package com.helix.core.storage.criteria

import com.helix.core.model.ArtifactRef
import com.helix.core.model.CriterionEvidenceSource
import com.helix.core.model.CriterionPendingReview
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.CriterionVerificationRecord
import com.helix.core.model.GoalId
import com.helix.core.model.GoalRunId
import com.helix.core.model.SessionId
import com.helix.core.model.Sha256
import com.helix.core.model.ToolCallId
import com.helix.core.model.TurnId
import com.helix.core.storage.internal.Value
import com.helix.core.storage.internal.asLong
import com.helix.core.storage.internal.asString

/** Versioned additions to the legacy criterion JSON. Missing additions remain legacy, never inferred. */
internal object CriterionVerificationCodec {
    fun encodeBinding(binding: CriterionVerificationBinding): String =
        "{\"version\":${binding.version},\"method\":${quote(binding.method.name)}," +
            "\"argument\":${quote(binding.argument)}}"

    fun decodeBinding(value: Value): CriterionVerificationBinding {
        val fields = fields(value, listOf("version", "method", "argument"))
        require(fields.getValue("version").asLong("version") == CriterionVerificationBinding.CURRENT_VERSION.toLong())
        return CriterionVerificationBinding(
            CriterionVerificationMethod.valueOf(fields.getValue("method").asString("method")),
            fields.getValue("argument").asString("argument"),
        )
    }

    fun encode(record: CriterionVerificationRecord): String =
        "{\"version\":${record.version},\"method\":${quote(record.method.name)}," +
            "\"bindingHash\":${quote(record.bindingHash.hex)}," +
            "\"goalId\":${quote(record.source.goalId.value)},\"runId\":${quote(record.source.runId.value)}," +
            "\"sessionId\":${quote(record.source.sessionId.value)},\"turnId\":${quote(record.source.turnId.value)}," +
            "\"contentHash\":${quote(record.contentHash.hex)},\"verifiedAt\":${record.verifiedAtEpochMillis}" +
            (record.sourceHash?.let { ",\"sourceHash\":" + quote(it.hex) } ?: "") + "}"

    fun decode(value: Value): CriterionVerificationRecord {
        val extra = if (value is Value.Obj && "sourceHash" in value.entries) listOf("sourceHash") else emptyList()
        val fields =
            fields(
                value,
                listOf(
                    "version",
                    "method",
                    "bindingHash",
                    "goalId",
                    "runId",
                    "sessionId",
                    "turnId",
                    "contentHash",
                    "verifiedAt",
                ) + extra,
            )
        require(fields.getValue("version").asLong("version") == CriterionVerificationRecord.CURRENT_VERSION.toLong())
        return CriterionVerificationRecord(
            method = CriterionVerificationMethod.valueOf(fields.getValue("method").asString("method")),
            bindingHash = Sha256(fields.getValue("bindingHash").asString("bindingHash")),
            source =
                CriterionEvidenceSource(
                    GoalId(fields.getValue("goalId").asString("goalId")),
                    GoalRunId(fields.getValue("runId").asString("runId")),
                    SessionId(fields.getValue("sessionId").asString("sessionId")),
                    TurnId(fields.getValue("turnId").asString("turnId")),
                ),
            contentHash = Sha256(fields.getValue("contentHash").asString("contentHash")),
            verifiedAtEpochMillis = fields.getValue("verifiedAt").asLong("verifiedAt"),
            sourceHash = fields["sourceHash"]?.let { Sha256(it.asString("sourceHash")) },
        )
    }

    fun encodeReview(review: CriterionPendingReview): String =
        "{\"version\":${review.version},\"toolCallId\":${quote(review.toolCallId.value)}," +
            "\"bindingHash\":${quote(review.bindingHash.hex)},\"sourceHash\":${quote(review.sourceHash.hex)}," +
            "\"artifactRef\":${review.artifactRef?.value?.let(::quote) ?: "null"}," +
            "\"reviewedAt\":${review.reviewedAtEpochMillis}}"

    fun decodeReview(value: Value): CriterionPendingReview {
        val fields =
            fields(value, listOf("version", "toolCallId", "bindingHash", "sourceHash", "artifactRef", "reviewedAt"))
        require(fields.getValue("version").asLong("version") == CriterionPendingReview.CURRENT_VERSION.toLong())
        val reference = fields.getValue("artifactRef")
        return CriterionPendingReview(
            ToolCallId(fields.getValue("toolCallId").asString("toolCallId")),
            Sha256(fields.getValue("bindingHash").asString("bindingHash")),
            Sha256(fields.getValue("sourceHash").asString("sourceHash")),
            if (reference == Value.Null) null else ArtifactRef(reference.asString("artifactRef")),
            fields.getValue("reviewedAt").asLong("reviewedAt"),
        )
    }

    private fun fields(
        value: Value,
        names: List<String>,
    ): LinkedHashMap<String, Value> {
        val entries = (value as? Value.Obj)?.entries ?: errorValue()
        require(entries.keys.toList() == names) { "invalid criterion verification fields" }
        return entries
    }

    private fun errorValue(): Nothing = throw IllegalArgumentException("criterion verification must be an object")

    private fun quote(value: String): String = "\"${CriteriaCodec.escape(value)}\""
}
