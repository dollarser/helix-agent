package com.helix.core.storage.repository

import com.helix.core.model.TurnState
import com.helix.core.storage.SearchText
import com.helix.core.storage.dao.SessionAccessDao
import com.helix.core.storage.dao.SessionAccessExecution
import com.helix.core.storage.dao.SessionAccessSummary
import com.helix.core.storage.dao.SessionHistoryDao

/** An immutable resolved scope, not authority supplied by tool arguments. */
class SessionAccess internal constructor(
    val sourceSessionId: String,
    val targetSessionId: String,
    val revision: Long,
    val readHistory: Boolean,
    val manageMetadata: Boolean,
    val delegateTasks: Boolean = false,
)

@Suppress("TooManyFunctions") // One narrow command/query boundary for user grants and scoped model metadata.
class SessionAccessRepository(
    private val dao: SessionAccessDao,
    private val history: SessionHistoryDao,
) {
    /** Called only by the explicit user scope editor, never by model tools. */
    fun setGrant(
        source: String,
        target: String,
        read: Boolean,
        manage: Boolean,
        delegateTasks: Boolean = false,
    ) = dao.setGrant(source, target, read, manage, delegateTasks)

    fun grants(source: String) = dao.grants(source)

    fun resolve(
        caller: SessionHistoryCaller,
        target: String,
        manage: Boolean = false,
        metadataOnly: Boolean = false,
    ): SessionAccess {
        active(caller)
        if (target == caller.sessionId) return SessionAccess(target, target, 0L, true, true)
        val grant = requireNotNull(dao.grant(caller.sessionId, target)) { UNAVAILABLE }
        require(
            if (manage) {
                grant.manageMetadata
            } else if (metadataOnly) {
                grant.readHistory || grant.manageMetadata || grant.delegateTasks
            } else {
                grant.readHistory
            },
        ) { UNAVAILABLE }
        requireNotNull(history.sourceSession(target)) { UNAVAILABLE }
        return SessionAccess(
            grant.sourceSessionId,
            grant.targetSessionId,
            grant.revision,
            grant.readHistory,
            grant.manageMetadata,
            grant.delegateTasks,
        )
    }

    fun validate(
        caller: SessionHistoryCaller,
        access: SessionAccess,
    ) {
        active(caller)
        require(caller.sessionId == access.sourceSessionId) { UNAVAILABLE }
        if (access.targetSessionId == caller.sessionId) {
            require(access.revision == 0L && access.readHistory && access.manageMetadata) { UNAVAILABLE }
            return
        }
        val grant = requireNotNull(dao.grant(caller.sessionId, access.targetSessionId)) { UNAVAILABLE }
        require(
            grant.revision == access.revision && grant.readHistory == access.readHistory &&
                grant.manageMetadata == access.manageMetadata && grant.delegateTasks == access.delegateTasks,
        ) { UNAVAILABLE }
        require(grant.readHistory || grant.manageMetadata || grant.delegateTasks) { UNAVAILABLE }
        requireNotNull(history.sourceSession(access.targetSessionId)) { UNAVAILABLE }
    }

    fun status(
        caller: SessionHistoryCaller,
        access: SessionAccess,
    ): com.helix.core.storage.entity.SessionEntity {
        validate(caller, access)
        val result = requireNotNull(history.sourceSession(access.targetSessionId)) { UNAVAILABLE }
        validate(caller, access)
        return result
    }

    /** Scoped state discovery for CAS Steer, with revocation checked before and after disclosure. */
    fun execution(
        caller: SessionHistoryCaller,
        access: SessionAccess,
    ): SessionAccessExecution? {
        validate(caller, access)
        val result = dao.execution(access.targetSessionId, TurnState.entries.filter { it.isTerminal }.map { it.name })
        validate(caller, access)
        return result
    }

    fun list(
        caller: SessionHistoryCaller,
        afterTargetId: String? = null,
        limit: Int = 20,
    ): List<SessionAccessSummary> {
        require(limit in 1..40)
        active(caller)
        val result = dao.list(caller.sessionId, afterTargetId, limit)
        result.forEach { row ->
            val access = resolve(caller, row.targetSessionId, metadataOnly = true)
            require(access.revision == row.revision) { UNAVAILABLE }
        }
        active(caller)
        return result
    }

    fun rename(
        caller: SessionHistoryCaller,
        access: SessionAccess,
        expectedTitle: String,
        title: String,
    ): Boolean {
        require(caller.toolName == "session.rename" && access.manageMetadata)
        require(expectedTitle.length <= 200 && title.isNotBlank() && title.length <= 200 && '\u0000' !in title)
        validate(caller, access)
        return dao.rename(
            caller.sessionId,
            access.targetSessionId,
            access.revision,
            caller.turnId,
            caller.callId,
            expectedTitle,
            title.trim(),
            SearchText.fold(title.trim()),
        ) ==
            1
    }

    fun archive(
        caller: SessionHistoryCaller,
        access: SessionAccess,
        expectedArchived: Boolean,
    ): Boolean {
        require(caller.toolName in setOf("session.archive", "session.restore") && access.manageMetadata)
        validate(caller, access)
        val archivedAt = if (caller.toolName == "session.archive") System.currentTimeMillis() else null
        return dao.archive(
            caller.sessionId,
            access.targetSessionId,
            access.revision,
            caller.turnId,
            caller.callId,
            caller.toolName,
            expectedArchived,
            archivedAt,
        ) ==
            1
    }

    /** Explicit UI commands share the same metadata-only boundary, without granting model access. */
    fun userRename(
        target: String,
        title: String,
    ): Boolean {
        require(title.isNotBlank() && title.length <= 200 && '\u0000' !in title)
        val current = requireNotNull(history.sourceSession(target)) { UNAVAILABLE }
        return dao.userRename(target, current.title, title.trim(), SearchText.fold(title.trim())) == 1
    }

    fun userSetArchived(
        target: String,
        archived: Boolean,
        now: Long,
    ): Boolean {
        val current = requireNotNull(history.sourceSession(target)) { UNAVAILABLE }
        return dao.userArchive(target, current.archivedAt != null, if (archived) now else null) == 1
    }

    private fun active(caller: SessionHistoryCaller) {
        requireNotNull(history.caller(caller.sessionId, caller.turnId, caller.callId, caller.toolName)) { UNAVAILABLE }
    }

    private companion object {
        const val UNAVAILABLE = "SESSION_DATA_UNAVAILABLE"
    }
}
