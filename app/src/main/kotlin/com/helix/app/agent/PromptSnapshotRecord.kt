package com.helix.app.agent

import com.helix.core.agent.PromptSnapshot
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
): PromptSnapshotRecord? {
    if (snapshot.sections.isEmpty() && snapshot.omittedSections.isEmpty()) return null
    val sections =
        buildJsonArray {
            val considered =
                (snapshot.sections + snapshot.omittedSections).sortedWith(
                    compareBy({ it.order }, { it.name }),
                )
            considered.forEach { section ->
                add(
                    buildJsonObject {
                        put("name", section.name)
                        put("order", section.order)
                        put("scope", section.scope.name)
                        put("source", section.source.name)
                        put("trust", section.trust.name)
                        put("contentHash", section.contentHash)
                        put("included", section.omissionReason == null)
                        put("reason", section.omissionReason?.name ?: "NON_EMPTY")
                        val bytes = TokenEstimator.utf8Bytes(section.content)
                        put("utf8Bytes", bytes)
                        put("estimatedTextTokens", TokenEstimator.estimateTokens(bytes))
                    },
                )
            }
        }
    return PromptSnapshotRecord(
        modelCallId = modelCallId,
        turnId = turnId,
        fingerprint = snapshot.fingerprint,
        sectionsJson = sections.toString(),
        auditPayload =
            buildJsonObject {
                put("version", 2)
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
