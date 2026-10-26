package com.helix.core.storage.repository

import com.helix.core.model.PlanArtifact
import com.helix.core.storage.dao.PlanDao
import com.helix.core.storage.entity.PlanEntity
import com.helix.core.storage.mapping.EntityMappers
import com.helix.core.storage.mapping.enumByName

/** Documents own proposed content; Room owns approval, execution and immutable reviewed evidence. */
class PlanRepository(
    private val dao: PlanDao,
    root: java.nio.file.Path,
) {
    private val documents =
        com.helix.core.storage.content
            .PrivateDocumentStorage(root)

    private fun reference(id: String): String = "plans/${com.helix.core.model.PlanId(id).value}.json"

    /** Publish content first. A failed/rolled-back Room insert leaves a document, never execution authority. */
    fun save(
        artifact: PlanArtifact,
        state: String,
        evidenceRef: String?,
    ): PlanEntity {
        enumByName(state, PlanLifecycleState::class.java, "plan state")
        check(dao.byId(artifact.id.value) == null) { "plan already exists" }
        val body = artifact.toStorageString().toByteArray(Charsets.UTF_8)
        val ref = reference(artifact.id.value)
        if (ref in documents.references("plans")) {
            check(documents.read(ref, MAX_BYTES).bytes.contentEquals(body)) { "PLAN_DOCUMENT_CONFLICT" }
        } else {
            documents.save("plans", "${artifact.id.value}.json", body, mimeType = "application/json")
        }
        val entity =
            EntityMappers.planEntityFor(artifact, state, evidenceRef).copy(
                approvedSnapshot = artifact.toStorageString().takeIf { state in APPROVED_STATES },
            )
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): PlanArtifact = resolveEntity(id).let(::resolveArtifact)

    private fun resolveArtifact(entity: PlanEntity): PlanArtifact {
        val snapshot = entity.approvedSnapshot
        check(entity.state !in APPROVED_STATES || snapshot != null) { "PLAN_APPROVAL_EVIDENCE_MISSING" }
        val text =
            snapshot ?: Charsets.UTF_8
                .newDecoder()
                .decode(
                    java.nio.ByteBuffer.wrap(documents.read(reference(entity.id), MAX_BYTES).bytes),
                ).toString()
        val artifact = PlanArtifact.parse(text)
        check(
            artifact.id.value == entity.id && artifact.version == entity.version &&
                artifact.objective == entity.objective && artifact.sha256().hex == entity.hash,
        ) { "PLAN_DOCUMENT_CHANGED" }
        return artifact
    }

    fun resolveEntity(id: String): PlanEntity = dao.byId(id) ?: throw IllegalArgumentException("plan not found: $id")

    fun resolveOrNull(id: String): PlanArtifact? = dao.byId(id)?.let(::resolveArtifact)

    fun list(): List<PlanEntity> = dao.list()

    /** Callers pair this with audit in their Room transaction. Approval captures the verified document atomically. */
    fun review(
        id: String,
        state: String,
        evidenceRef: String?,
    ) {
        require(state in setOf("APPROVED", "REJECTED", "DRAFT"))
        val entity = resolveEntity(id)
        check(entity.state == "READY") { "PLAN_NOT_READY_FOR_REVIEW" }
        val snapshot =
            if (state == PlanLifecycleState.APPROVED.name) {
                resolveArtifact(entity).toStorageString()
            } else {
                entity.approvedSnapshot
            }
        check(dao.review(id, state, evidenceRef, snapshot) == 1) { "PLAN_REVIEW_CHANGED" }
    }

    fun transitionFromApproved(
        id: String,
        state: String,
        evidenceRef: String?,
    ): Int {
        enumByName(state, PlanLifecycleState::class.java, "plan state")
        return dao.transitionFromApproved(id, state, evidenceRef)
    }

    fun delete(id: String) {
        val ref = reference(id)
        val file = if (ref in documents.references("plans")) documents.read(ref, MAX_BYTES) else null
        require(dao.delete(id) == 1) { "plan not found or still referenced: $id" }
        // A file cleanup failure cannot roll back the committed lifecycle deletion; never claim otherwise.
        if (file != null) documents.delete(file)
    }

    private companion object {
        const val MAX_BYTES = 1_048_576
        val APPROVED_STATES = setOf("APPROVED", "EXECUTING")
    }
}

/** Plan lifecycle states persisted in `plans.state` (doc 9.1: `state`). */
enum class PlanLifecycleState {
    DRAFT,
    READY,
    APPROVED,
    EXECUTING,
    SUPERSEDED,
    REJECTED,
}
