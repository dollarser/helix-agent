package com.helix.app.chat

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.tools.framework.JobObservation
import com.helix.tools.framework.JobObservationEvidence
import kotlinx.serialization.json.JsonObject

/** Immutable observations in the existing audit journal, not an execution database or a second chat history. */
internal class JobObservationJournal(
    private val storage: HelixStorage,
) {
    fun record(observation: JobObservation) {
        val payload = JobObservationEvidence.encode(observation)
        val stable = JsonObject(payload.filterKeys { it != "observedAtMillis" }).toString()
        val id = "job-observed-" + FileContentStore.sha256Hex(stable.toByteArray())
        storage.withTransaction {
            val previous =
                try {
                    storage.auditEvents.resolve(id)
                } catch (_: IllegalArgumentException) {
                    null
                }
            if (previous == null) {
                storage.auditEvents.append(
                    id,
                    observation.binding.sessionId,
                    TYPE,
                    "platform",
                    payload.toString(),
                    observation.observedAtMillis,
                )
            } else {
                check(previous.type == TYPE && previous.correlationId == observation.binding.sessionId)
            }
        }
    }

    companion object {
        const val TYPE = "runtime.job_observation"
    }
}
