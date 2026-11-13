package com.helix.app.session

import com.helix.core.model.input.SessionInputSpec
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.SessionHistoryCaller
import com.helix.core.storage.repository.SessionInputRecord

internal class RoomSessionDelegationStore(
    private val storage: HelixStorage,
) : SessionDelegationService.Store {
    override fun <T> transaction(action: () -> T): T {
        var value: T? = null
        storage.withTransaction { value = action() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    override fun authorize(
        caller: SessionHistoryCaller,
        target: String,
    ): Long {
        storage.sessionHistory.caller(caller)
        require(
            caller.toolName in setOf("session.queue", "session.steer") && caller.sessionId != target,
        ) { UNAVAILABLE }
        val access = storage.sessionAccess.resolve(caller, target, metadataOnly = true)
        require(access.delegateTasks) { UNAVAILABLE }
        require(storage.sessionAccess.status(caller, access).archivedAt == null) { UNAVAILABLE }
        return access.revision
    }

    override fun receipt(
        source: String,
        key: String,
    ) = storage.sessionInputs.delegationReceipt(source, key)

    override fun text(record: SessionInputRecord) = storage.sessionInputs.readText(record)

    override fun accept(spec: SessionInputSpec) = storage.sessionInputs.accept(spec)

    override fun turnState(turnId: String) = storage.turns.find(turnId)?.state

    private companion object {
        const val UNAVAILABLE = "SESSION_DATA_UNAVAILABLE"
    }
}
