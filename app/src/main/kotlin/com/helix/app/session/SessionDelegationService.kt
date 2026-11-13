package com.helix.app.session

import com.helix.core.model.input.InputConfiguration
import com.helix.core.model.input.ModelDelegationOrigin
import com.helix.core.model.input.SessionInputDelivery
import com.helix.core.model.input.SessionInputSpec
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.SessionHistoryCaller
import com.helix.core.storage.repository.SessionInputAcceptResult
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputRepository
import java.util.UUID

/** Original session input rows own both delivery and its receipt; this service never runs a Turn. */
class SessionDelegationService internal constructor(
    private val store: Store,
    private val configuration: suspend (String, String?) -> InputConfiguration,
    private val wakeTarget: (String) -> Unit,
    private val now: () -> Long,
    private val id: () -> String,
) {
    constructor(
        storage: HelixStorage,
        configuration: suspend (String, String?) -> InputConfiguration,
        wakeTarget: (String) -> Unit,
        now: () -> Long = System::currentTimeMillis,
        id: () -> String = { UUID.randomUUID().toString() },
    ) : this(RoomSessionDelegationStore(storage), configuration, wakeTarget, now, id)

    internal interface Store {
        fun <T> transaction(action: () -> T): T

        /** Validates the original running call, explicit target grant, and archive state. */
        fun authorize(
            caller: SessionHistoryCaller,
            target: String,
        ): Long

        fun receipt(
            source: String,
            key: String,
        ): SessionInputRecord?

        fun text(record: SessionInputRecord): String

        fun accept(spec: SessionInputSpec): SessionInputAcceptResult

        fun turnState(turnId: String): String?
    }

    data class Receipt(
        val record: SessionInputRecord,
        val duplicate: Boolean,
        val targetTurnState: String?,
        val observerNotified: Boolean = false,
    )

    suspend fun queue(
        caller: SessionHistoryCaller,
        targetSessionId: String,
        requestKey: String,
        text: String,
        checkActive: () -> Unit = {},
    ): Receipt =
        deliver(caller, Delivery(targetSessionId, requestKey, text, SessionInputDelivery.QUEUE, null), checkActive)

    suspend fun steer(
        caller: SessionHistoryCaller,
        targetSessionId: String,
        expectedTurnId: String,
        requestKey: String,
        text: String,
        checkActive: () -> Unit = {},
    ): Receipt =
        deliver(
            caller,
            Delivery(targetSessionId, requestKey, text, SessionInputDelivery.STEER, expectedTurnId),
            checkActive,
        )

    private data class Delivery(
        val target: String,
        val key: String,
        val text: String,
        val kind: SessionInputDelivery,
        val expectedTurnId: String?,
    )

    private suspend fun deliver(
        caller: SessionHistoryCaller,
        delivery: Delivery,
        checkActive: () -> Unit,
    ): Receipt {
        validate(caller, delivery)
        // Completed or interrupted target Turns do not invalidate their already accepted receipt.
        val (initialRevision, existing) =
            store.transaction {
                checkActive()
                val revision = store.authorize(caller, delivery.target)
                revision to previous(caller, delivery)
            }
        if (existing != null) return existing
        val targetConfiguration = configuration(delivery.target, delivery.expectedTurnId)
        val receipt =
            store.transaction {
                checkActive()
                val revision = store.authorize(caller, delivery.target)
                previous(caller, delivery)?.let { return@transaction it }
                require(revision == initialRevision) { "SESSION_DATA_UNAVAILABLE" }
                accept(
                    SessionInputSpec(
                        inputId = id(),
                        sessionId = delivery.target,
                        delivery = delivery.kind,
                        expectedTurnId = delivery.expectedTurnId,
                        revision = 0,
                        text = delivery.text,
                        attachments = emptyList(),
                        configuration = targetConfiguration,
                        createdAt = now(),
                        delegation =
                            ModelDelegationOrigin(
                                caller.sessionId,
                                caller.turnId,
                                caller.callId,
                                delivery.key,
                                revision,
                            ),
                    ),
                )
            }
        // Durable acceptance is not undone by source cancellation or an advisory observer failure.
        return if (receipt.duplicate) receipt else receipt.copy(observerNotified = notify(delivery.target))
    }

    private fun validate(
        caller: SessionHistoryCaller,
        delivery: Delivery,
    ) {
        val expectedTool = if (delivery.kind == SessionInputDelivery.QUEUE) "session.queue" else "session.steer"
        require(caller.toolName == expectedTool)
        require(delivery.target.isNotBlank() && delivery.target.length <= 256 && '\u0000' !in delivery.target)
        require(delivery.key.isNotBlank() && delivery.key.length <= 128 && '\u0000' !in delivery.key)
        require(delivery.text.isNotBlank() && delivery.text.length <= SessionInputRepository.MAX_TEXT_CHARS)
        require('\u0000' !in delivery.text)
        delivery.expectedTurnId?.let { require(it.isNotBlank() && it.length <= 128 && '\u0000' !in it) }
    }

    private fun previous(
        caller: SessionHistoryCaller,
        delivery: Delivery,
    ): Receipt? {
        val previous = store.receipt(caller.sessionId, delivery.key) ?: return null
        require(
            previous.sessionId == delivery.target && previous.delivery == delivery.kind &&
                previous.expectedTurnId == delivery.expectedTurnId && store.text(previous) == delivery.text,
        ) { "SESSION_REQUEST_CONFLICT" }
        return Receipt(previous, true, (previous.consumedTurnId ?: previous.expectedTurnId)?.let(store::turnState))
    }

    private fun accept(spec: SessionInputSpec): Receipt =
        when (val result = store.accept(spec)) {
            is SessionInputAcceptResult.Accepted -> {
                Receipt(
                    result.record,
                    result.duplicate,
                    (result.record.consumedTurnId ?: result.record.expectedTurnId)?.let(store::turnState),
                )
            }

            is SessionInputAcceptResult.Rejected -> {
                throw IllegalArgumentException(result.reason)
            }
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // An observer cannot roll back the durable receipt.
    private fun notify(target: String): Boolean =
        try {
            wakeTarget(target)
            true
        } catch (_: Exception) {
            false
        }
}
