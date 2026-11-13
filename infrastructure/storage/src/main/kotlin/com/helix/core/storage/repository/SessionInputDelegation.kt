package com.helix.core.storage.repository

import com.helix.core.model.input.ModelDelegationOrigin
import com.helix.core.model.input.SessionInputDelivery
import com.helix.core.model.input.SessionInputSpec
import com.helix.core.storage.HelixDatabase
import com.helix.core.storage.entity.SessionInputEntity

/** Queries inside the original input owner's transaction, not another delivery lifecycle. */
internal class SessionInputDelegation(
    private val database: HelixDatabase,
) {
    fun targetAllowed(
        sourceTurnId: String,
        targetSessionId: String,
    ): Boolean = AncestryWalk(database, targetSessionId).visit(sourceTurnId)

    fun allowed(record: SessionInputRecord): Boolean {
        val origin = record.delegation ?: return true
        val grant = database.sessionAccessDao().grant(origin.sourceSessionId, record.sessionId)
        val target = database.sessionDao().byId(record.sessionId)
        val currentGrant = grant?.delegateTasks == true && grant.revision == origin.grantRevision
        val liveTarget = target != null && target.archivedAt == null
        return currentGrant && liveTarget && targetAllowed(origin.sourceTurnId, record.sessionId)
    }

    fun validAcceptance(spec: SessionInputSpec): Boolean {
        val origin = requireNotNull(spec.delegation)
        val grant = database.sessionAccessDao().grant(origin.sourceSessionId, spec.sessionId)
        val target = database.sessionDao().byId(spec.sessionId)
        val caller =
            database.sessionHistoryDao().caller(
                origin.sourceSessionId,
                origin.sourceTurnId,
                origin.sourceCallId,
                if (spec.delivery == SessionInputDelivery.QUEUE) "session.queue" else "session.steer",
            )
        val currentGrant = grant?.delegateTasks == true && grant.revision == origin.grantRevision
        val liveTarget = target != null && target.archivedAt == null
        return caller != null && currentGrant && liveTarget
    }

    /** Bounded DFS rejects corrupt cycles; completed shared ancestors are safe to revisit. */
    private class AncestryWalk(
        private val database: HelixDatabase,
        private val target: String,
    ) {
        private val completed = mutableSetOf<String>()
        private val visiting = mutableSetOf<String>()

        fun visit(turnId: String): Boolean =
            when {
                turnId in visiting -> false
                turnId in completed -> true
                visiting.size + completed.size >= MAX_ANCESTORS -> false
                else -> enter(turnId)
            }

        private fun enter(turnId: String): Boolean {
            val turn = database.turnDao().byId(turnId)
            if (turn == null || turn.sessionId == target) return false
            visiting.add(turnId)
            val parents = database.sessionInputDao().delegationParents(turnId)
            val allowed =
                parents.size <= MAX_ANCESTORS &&
                    parents.all { parent ->
                        val ancestor = database.turnDao().byId(parent.turnId)
                        parent.sessionId != target && ancestor?.sessionId == parent.sessionId && visit(parent.turnId)
                    }
            visiting.remove(turnId)
            if (allowed) completed.add(turnId)
            return allowed
        }
    }

    companion object {
        private const val MAX_ANCESTORS = 64

        fun origin(row: SessionInputEntity): ModelDelegationOrigin? {
            val fields =
                listOf(
                    row.delegationSourceSessionId,
                    row.delegationSourceTurnId,
                    row.delegationSourceCallId,
                    row.delegationRequestKey,
                )
            if (row.inputOrigin == "USER") {
                require(fields.all { it == null } && row.delegationGrantRevision == null)
                return null
            }
            require(row.inputOrigin == "MODEL_DELEGATION")
            return ModelDelegationOrigin(
                requireNotNull(row.delegationSourceSessionId),
                requireNotNull(row.delegationSourceTurnId),
                requireNotNull(row.delegationSourceCallId),
                requireNotNull(row.delegationRequestKey),
                requireNotNull(row.delegationGrantRevision),
            )
        }
    }
}
