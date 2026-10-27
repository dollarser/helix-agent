package com.helix.app.agent

import com.helix.core.agent.PromptContentIdentity
import com.helix.core.agent.PromptSnapshot
import com.helix.core.agent.ResolvedPromptSection
import com.helix.core.agent.TokenEstimator
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The redacted record of one request's system prompt (research doc section 4.4): per-section
 * provenance and content hash — name, order, scope, source, trust, contentHash — NEVER the
 * section content, so the `model_calls` row and the `prompt.assembled` audit event prove WHICH
 * sources and which version of content a request used without persisting prompt bodies (the
 * same redaction convention as the tool-dispatch audit rows).
 */
internal data class PromptSnapshotRecord(
    val modelCallId: String,
    val turnId: String,
    val fingerprint: String,
    val sectionsJson: String,
    val auditPayload: String,
)

/**
 * Builds the redacted record for [snapshot]. Null only when no section was considered.
 */
internal fun promptSnapshotRecord(
    snapshot: PromptSnapshot,
    modelCallId: String,
    turnId: String,
    compacting: Boolean = false,
): PromptSnapshotRecord? {
    if (snapshot.sections.isEmpty() && snapshot.omittedSections.isEmpty()) return null
    val sections =
        buildJsonArray {
            (snapshot.sections + snapshot.omittedSections)
                .sortedWith(compareBy({ it.order }, { it.name }))
                .forEach { add(it.redactedRecord()) }
        }
    return PromptSnapshotRecord(
        modelCallId = modelCallId,
        turnId = turnId,
        fingerprint = snapshot.fingerprint,
        sectionsJson = sections.toString(),
        auditPayload =
            buildJsonObject {
                put("contextKind", if (compacting) "COMPACTION" else "TASK")
                put("estimateMethod", "utf8_bytes_ceil_div_4")
                put("promptUtf8Bytes", TokenEstimator.utf8Bytes(snapshot.content))
                put(
                    "promptEstimatedTextTokens",
                    TokenEstimator.estimateTokens(TokenEstimator.utf8Bytes(snapshot.content)),
                )
                put("modelCallId", modelCallId)
                put("turnId", turnId)
                put("fingerprint", snapshot.fingerprint)
                put("sections", sections)
            }.toString(),
    )
}

private fun ResolvedPromptSection.redactedRecord() =
    buildJsonObject {
        put("name", name)
        put("order", order)
        put("scope", scope.name)
        put("source", source.name)
        put("trust", trust.name)
        put("contentHash", contentHash)
        put("retention", retention.name)
        put("priority", priority)
        identity?.let { put("identity", it.redactedIdentity()) }
        put("rereadable", readReference != null)
        readReference?.let { reference ->
            put(
                "readReference",
                buildJsonObject {
                    put("tool", reference.tool)
                    put(
                        "arguments",
                        kotlinx.serialization.json.Json
                            .parseToJsonElement(reference.argumentsJson),
                    )
                },
            )
        }
        put("included", omissionReason == null)
        put("reason", omissionReason?.name ?: "NON_EMPTY")
        val bytes = TokenEstimator.utf8Bytes(content)
        put("utf8Bytes", bytes)
        put("estimatedTextTokens", TokenEstimator.estimateTokens(bytes))
    }

private fun PromptContentIdentity.redactedIdentity() =
    buildJsonObject {
        put("owner", owner)
        put("revision", revision)
        resource?.let { put("resource", it) }
        put(
            "owners",
            buildJsonArray {
                owners.forEach { owner ->
                    add(
                        buildJsonObject {
                            put("id", owner.id)
                            put("runtimeId", owner.runtimeId)
                            put("version", owner.version)
                            put("revision", owner.revision)
                            put("fingerprint", owner.fingerprint)
                        },
                    )
                }
            },
        )
    }
