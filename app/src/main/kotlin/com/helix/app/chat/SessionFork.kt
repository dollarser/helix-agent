package com.helix.app.chat

import com.helix.app.agent.ContextCompaction
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.MessageAttachmentRepository
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Runs in one Room transaction. No tool dispatch, Provider call, Goal or approval is copied. */
internal class SessionFork(
    private val storage: HelixStorage,
) {
    fun create(
        sourceSessionId: String,
        messageId: String,
        newSessionId: String,
        title: String,
        now: Long,
        checkActive: () -> Unit = {},
    ) {
        storage.withTransaction {
            checkActive()
            val source = storage.sessions.resolve(sourceSessionId)
            val plan = SessionForkPlan.prepare(storage, sourceSessionId, messageId, checkActive)
            storage.sessions.create(newSessionId, title, source.providerId, source.modelId, now)
            val identities = plan.rows.associate { it.id to UUID.randomUUID().toString() }
            val sequences = mutableMapOf<Long, Long>()
            val artifacts = mutableMapOf<String, String>()
            for (row in plan.rows) {
                checkActive()
                if (row.kind == ContextCompaction.KIND) {
                    if (row.id == plan.checkpointId) copyCheckpoint(plan, newSessionId, identities, sequences)
                    continue
                }
                val copied = storage.messages.copyHistory(row, identities.getValue(row.id), newSessionId)
                sequences[row.sequence] = copied.sequence
                storage.messageAttachments.bind(
                    copied.id,
                    storage.messageAttachments.listByMessage(row.id).map {
                        val artifact =
                            artifacts.getOrPut(it.artifactId) {
                                storage.artifacts
                                    .copyReference(
                                        it.artifactId,
                                        UUID.randomUUID().toString(),
                                        newSessionId,
                                    ).id
                            }
                        MessageAttachmentRepository.Binding(artifact, it.purpose, it.boundSha256)
                    },
                )
            }
            storage.messages.append(
                UUID.randomUUID().toString(),
                newSessionId,
                null,
                "SYSTEM",
                SessionForkPlan.KIND,
                origin(sourceSessionId, messageId, plan, identities),
            )
            storage.auditEvents.append(
                UUID.randomUUID().toString(),
                newSessionId,
                "session.fork",
                "user",
                buildJsonObject {
                    put("sourceSessionId", sourceSessionId)
                    put("boundaryMessageId", plan.boundaryId)
                }.toString(),
                now,
            )
            checkActive()
        }
    }

    private fun origin(
        sourceSessionId: String,
        messageId: String,
        plan: SessionForkPlan,
        identities: Map<String, String>,
    ): String =
        buildJsonObject {
            put("formatVersion", 1)
            put("sourceSessionId", sourceSessionId)
            put("requestedMessageId", messageId)
            put("boundaryMessageId", plan.boundaryId)
            put("sourceCheckpointId", plan.checkpointId)
            put(
                "messages",
                buildJsonArray {
                    plan.rows.filter { it.kind != ContextCompaction.KIND }.forEach {
                        add(
                            buildJsonObject {
                                put("sourceId", it.id)
                                put("messageId", identities.getValue(it.id))
                            },
                        )
                    }
                },
            )
        }.toString()

    private fun copyCheckpoint(
        plan: SessionForkPlan,
        sessionId: String,
        identities: Map<String, String>,
        sequences: Map<Long, Long>,
    ) {
        val checkpoint = plan.checkpoint ?: return
        val through = sequences.filterKeys { it <= checkpoint.coveredThrough }.values.maxOrNull()
        require(through != null) { "FORK_CHECKPOINT" }
        val content =
            buildJsonObject {
                put("coveredThrough", through)
                put("summary", checkpoint.summary)
                put(
                    "preservedMessageIds",
                    buildJsonArray {
                        checkpoint.preservedMessageIds.forEach {
                            add(
                                kotlinx.serialization.json.JsonPrimitive(identities.getValue(it)),
                            )
                        }
                    },
                )
            }.toString()
        storage.messages.append(
            identities.getValue(requireNotNull(plan.checkpointId)),
            sessionId,
            null,
            "ASSISTANT",
            ContextCompaction.KIND,
            content,
        )
    }
}
