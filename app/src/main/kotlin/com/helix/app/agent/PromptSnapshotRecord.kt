package com.helix.app.agent

import com.helix.core.agent.PromptSnapshot
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
 * Builds the redacted record for [snapshot]. Null when the assembly had no non-blank sections
 * (nothing sent, nothing to record).
 */
internal fun promptSnapshotRecord(
    snapshot: PromptSnapshot,
    modelCallId: String,
    turnId: String,
): PromptSnapshotRecord? {
    if (snapshot.sections.isEmpty()) return null
    val sections =
        buildJsonArray {
            snapshot.sections.forEach { section ->
                add(
                    buildJsonObject {
                        put("name", section.name)
                        put("order", section.order)
                        put("scope", section.scope.name)
                        put("source", section.source.name)
                        put("trust", section.trust.name)
                        put("contentHash", section.contentHash)
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
                put("modelCallId", modelCallId)
                put("turnId", turnId)
                put("fingerprint", snapshot.fingerprint)
                put("sections", sections)
            }.toString(),
    )
}
