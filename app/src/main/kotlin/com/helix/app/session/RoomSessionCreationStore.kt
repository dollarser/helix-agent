package com.helix.app.session

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.SessionCreationGrantEntity
import com.helix.core.storage.entity.SessionCreationReceiptEntity
import com.helix.core.storage.repository.SessionHistoryCaller
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class RoomSessionCreationStore(
    private val storage: HelixStorage,
) : SessionCreationService.Store {
    override fun <T> transaction(action: () -> T): T {
        var value: T? = null
        storage.withTransaction { value = action() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    override fun validateCaller(caller: SessionHistoryCaller) {
        storage.sessionHistory.caller(caller)
    }

    override fun grant(source: String) = storage.sessionCreation.grant(source)

    override fun saveGrant(grant: SessionCreationGrantEntity) = storage.sessionCreation.saveGrant(grant)

    override fun receipt(
        source: String,
        key: String,
    ) = storage.sessionCreation.receipt(source, key)

    override fun insert(receipt: SessionCreationReceiptEntity) = storage.sessionCreation.insert(receipt)

    override fun auditGrant(
        source: String,
        allowed: Boolean,
        revision: Long,
        at: Long,
    ) {
        storage.auditEvents.append(
            java.util.UUID
                .randomUUID()
                .toString(),
            source,
            "session.creation_grant",
            "user",
            buildJsonObject {
                put("allowed", allowed)
                put("revision", revision)
            }.toString(),
            at,
        )
    }
}
