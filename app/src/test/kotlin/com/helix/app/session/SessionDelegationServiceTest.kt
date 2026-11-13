package com.helix.app.session

import com.helix.core.model.input.InputConfiguration
import com.helix.core.model.input.SessionInputDelivery
import com.helix.core.model.input.SessionInputSpec
import com.helix.core.storage.repository.SessionHistoryCaller
import com.helix.core.storage.repository.SessionInputAcceptResult
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDelegationServiceTest {
    private val caller = SessionHistoryCaller("source", "turn", "call", "session.queue")
    private val configuration = InputConfiguration("target-provider", "target-model", "CHAT", "target-fingerprint")

    @Test fun defaultDeniedAndStoppedCallCannotQueue() {
        val store = Store()
        val service = service(store)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { queue(service) } }
        store.allowed = true
        store.active = false
        assertThrows(IllegalArgumentException::class.java) { runBlocking { queue(service) } }
        assertEquals(0, store.accepts)
    }

    @Test fun downstreamDelegationRequiresTheReceivingSessionsOwnExplicitGrant() =
        runBlocking {
            val store = Store().apply { authorizedSource = "receiving" }
            val downstreamCaller = caller.copy(sessionId = "receiving", turnId = "receiving-turn", callId = "next-call")
            val service = service(store)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { service.queue(downstreamCaller, "downstream", "next", "task") }
            }
            store.allowed = true
            val receipt = service.queue(downstreamCaller, "downstream", "next", "task")
            assertEquals("receiving", receipt.record.delegation?.sourceSessionId)
            assertEquals("receiving-turn", receipt.record.delegation?.sourceTurnId)
            assertEquals("next-call", receipt.record.delegation?.sourceCallId)
        }

    @Test fun explicitlyGrantedCycleStillRejectsWithoutAcceptingInput() {
        val store =
            Store().apply {
                allowed = true
                targetAllowed = false
            }
        val failure = assertThrows(IllegalArgumentException::class.java) { runBlocking { queue(service(store)) } }
        assertEquals("SESSION_DELEGATION_CYCLE", failure.message)
        assertEquals(0, store.accepts)
    }

    @Test fun acceptedReceiptSurvivesChangedAncestryWithoutNewDelivery() =
        runBlocking {
            val store = Store().apply { allowed = true }
            var configurations = 0
            var wakes = 0
            val service =
                service(store, config = { _, _ ->
                    configurations++
                    configuration
                }, wake = { wakes++ })
            val accepted = queue(service)
            store.targetAllowed = false
            val retry = queue(service)
            assertEquals(accepted.record, retry.record)
            assertTrue(retry.duplicate)
            assertEquals(1, store.accepts)
            assertEquals(1, configurations)
            assertEquals(1, wakes)
        }

    @Test fun lostReplyAndRecreatedServiceReturnOriginalReceiptWithoutConfigurationOrWake() =
        runBlocking {
            val store = Store().apply { allowed = true }
            var wakes = 0
            val first = queue(service(store, wake = { wakes++ }))
            val retried =
                service(store, config = {
                    _,
                    _,
                    ->
                    error("Do not resolve config for an accepted input")
                }, wake = { wakes++ })
                    .queue(caller.copy(turnId = "later", callId = "retry"), "target", "key", " exact task ")
            assertEquals(first.record, retried.record)
            assertTrue(retried.duplicate)
            assertEquals("turn", retried.record.delegation?.sourceTurnId)
            assertEquals("call", retried.record.delegation?.sourceCallId)
            assertEquals(7L, retried.record.delegation?.grantRevision)
            assertEquals(configuration, retried.record.configuration)
            assertTrue(retried.record.attachments.isEmpty())
            assertTrue(retried.record.skillActivations.isEmpty())
            assertEquals(1, store.accepts)
            assertEquals(1, wakes)
        }

    @Test fun exactTextAndTargetConflictNeverDuplicate() =
        runBlocking {
            val store = Store().apply { allowed = true }
            val service = service(store)
            queue(service)
            listOf("target" to "exact task", "other" to " exact task ").forEach { (target, text) ->
                val failure =
                    assertThrows(IllegalArgumentException::class.java) {
                        runBlocking { service.queue(caller, target, "key", text) }
                    }
                assertEquals("SESSION_REQUEST_CONFLICT", failure.message)
            }
            assertEquals(1, store.accepts)
        }

    @Test fun changedGrantDuringConfigurationRejectsBeforeAcceptance() {
        val store = Store().apply { allowed = true }
        val service =
            service(store, config = { _, _ ->
                store.allowed = false
                configuration
            })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { queue(service) } }
        assertEquals(0, store.accepts)
    }

    @Test fun revokedAndRegrantedDuringSnapshotDoesNotRetargetInFlightAuthority() {
        val store = Store().apply { allowed = true }
        var wakes = 0
        val service =
            service(store, config = { _, _ ->
                store.allowed = false
                store.revision++
                store.allowed = true
                store.revision++
                configuration
            }, wake = { wakes++ })
        val error = assertThrows(IllegalArgumentException::class.java) { runBlocking { queue(service) } }
        assertEquals("SESSION_DATA_UNAVAILABLE", error.message)
        assertEquals(0, store.accepts)
        assertEquals(0, wakes)
        assertTrue(store.rows.isEmpty())
    }

    @Test fun concurrentlyAcceptedDuplicateUsesOriginalReceiptAfterGrantRevisionChanges() =
        runBlocking {
            val store = Store().apply { allowed = true }
            var wakes = 0
            val first = service(store, wake = { wakes++ })
            var committed: SessionInputRecord? = null
            val racing =
                service(store, config = { _, _ ->
                    committed = queue(first).record
                    store.revision += 2
                    configuration
                }, wake = { wakes++ })
            val receipt = queue(racing)
            assertEquals(committed, receipt.record)
            assertTrue(receipt.duplicate)
            assertEquals(1, store.accepts)
            assertEquals(1, wakes)
        }

    @Test fun revokedDuplicateDoesNotExposeReceiptOrChangeAppendedWork() =
        runBlocking {
            val store = Store().apply { allowed = true }
            val service = service(store)
            val accepted = queue(service).record
            store.rows["source" to "key"] =
                accepted.copy(state = SessionInputState.APPENDED, consumedTurnId = "target-turn")
            store.allowed = false
            assertThrows(IllegalArgumentException::class.java) { runBlocking { queue(service) } }
            assertEquals(SessionInputState.APPENDED, store.rows.getValue("source" to "key").state)
            assertEquals(1, store.accepts)
        }

    @Test fun observerFailureCannotDisguiseCommittedAcceptance() =
        runBlocking {
            val store = Store().apply { allowed = true }
            val receipt = queue(service(store, wake = { error("notification unavailable") }))
            assertFalse(receipt.observerNotified)
            assertEquals(SessionInputState.PENDING, receipt.record.state)
            assertEquals(1, store.rows.size)
        }

    @Test fun cancelledBeforeCommitAndQueueRejectionNeverWake() {
        val store = Store().apply { allowed = true }
        var checks = 0
        val service = service(store, wake = { error("must not wake") })
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            runBlocking {
                service.queue(caller, "target", "key", "task") {
                    if (++checks == 2) throw java.util.concurrent.CancellationException()
                }
            }
        }
        assertEquals(0, store.accepts)
        store.rejection = "INPUT_QUEUE_FULL"
        val failure = assertThrows(IllegalArgumentException::class.java) { runBlocking { queue(service) } }
        assertEquals("INPUT_QUEUE_FULL", failure.message)
        assertTrue(store.rows.isEmpty())
    }

    @Test fun receiptSeparatesContextModelRequestAndUnknownTurnState() =
        runBlocking {
            val store = Store().apply { allowed = true }
            val service = service(store)
            val accepted = queue(service).record
            store.rows["source" to "key"] =
                accepted.copy(
                    state = SessionInputState.APPENDED,
                    consumedTurnId = "target-turn",
                    requestModelCallId = "model-request",
                )
            store.targetTurnState = "NEEDS_REVIEW"
            val receipt = queue(service)
            assertEquals("NEEDS_REVIEW", receipt.targetTurnState)
            assertEquals("model-request", receipt.record.requestModelCallId)
            assertEquals(1, store.accepts)
        }

    @Test fun steerUsesExactTargetTurnConfigurationAndDoesNotQueue() =
        runBlocking {
            val store = Store().apply { allowed = true }
            var selected: Pair<String, String?>? = null
            val activeConfiguration = configuration.copy(fingerprint = "active-target-turn")
            val service =
                service(store, config = { target, turn ->
                    selected = target to turn
                    activeConfiguration
                })
            val receipt = service.steer(caller.copy(toolName = "session.steer"), "target", "target-turn", "key", "task")
            assertEquals("target" to "target-turn", selected)
            assertEquals(SessionInputDelivery.STEER, receipt.record.delivery)
            assertEquals("target-turn", receipt.record.expectedTurnId)
            assertEquals(activeConfiguration, receipt.record.configuration)
            assertEquals(1, store.accepts)
        }

    @Test fun steerReplyLossAfterTargetFinishesReturnsOriginalReceipt() =
        runBlocking {
            val store = Store().apply { allowed = true }
            val steerCaller = caller.copy(toolName = "session.steer")
            val accepted = service(store).steer(steerCaller, "target", "target-turn", "key", "task").record
            store.rows["source" to "key"] =
                accepted.copy(state = SessionInputState.APPENDED, consumedTurnId = "target-turn")
            store.targetTurnState = "COMPLETED"
            store.targetLive = false
            val retried =
                service(store, config = { _, _ -> error("Finished receipt must not resolve configuration") })
                    .steer(steerCaller.copy(turnId = "later", callId = "retry"), "target", "target-turn", "key", "task")
            assertTrue(retried.duplicate)
            assertEquals("COMPLETED", retried.targetTurnState)
            assertEquals("call", retried.record.delegation?.sourceCallId)
            assertEquals(1, store.accepts)
        }

    @Test fun deliveryKindOrExpectedTurnChangeConflictsWithOriginalRequest() =
        runBlocking {
            val store = Store().apply { allowed = true }
            val service = service(store)
            val steerCaller = caller.copy(toolName = "session.steer")
            service.steer(steerCaller, "target", "target-turn", "key", "task")
            val changed =
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { service.steer(steerCaller, "target", "new-turn", "key", "task") }
                }
            assertEquals("SESSION_REQUEST_CONFLICT", changed.message)
            val queued =
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { service.queue(caller, "target", "key", "task") }
                }
            assertEquals("SESSION_REQUEST_CONFLICT", queued.message)
            assertEquals(1, store.accepts)
        }

    @Test fun terminalRaceRejectsNewSteerWithoutQueueFallback() {
        val store = Store().apply { allowed = true }
        val service =
            service(
                store,
                config = { _, _ ->
                    store.targetLive = false
                    configuration
                },
                wake = { error("Rejected Steer must not wake") },
            )
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    service.steer(
                        caller.copy(toolName = "session.steer"),
                        "target",
                        "target-turn",
                        "key",
                        "task",
                    )
                }
            }
        assertEquals("INPUT_TARGET_UNAVAILABLE", failure.message)
        assertTrue(store.rows.isEmpty())
        assertEquals(0, store.accepts)
    }

    private suspend fun queue(service: SessionDelegationService) =
        service.queue(caller, "target", "key", " exact task ")

    private fun service(
        store: Store,
        config: suspend (String, String?) -> InputConfiguration = { _, _ -> configuration },
        wake: (String) -> Unit = {},
    ) = SessionDelegationService(store, config, wake, { 1L }, { "input" })

    private class Store : SessionDelegationService.Store {
        var allowed = false
        var active = true
        var revision = 7L
        var authorizedSource = "source"
        var targetAllowed = true
        var accepts = 0
        var rejection: String? = null
        var targetTurnState: String? = null
        var targetLive = true
        val rows = mutableMapOf<Pair<String, String>, SessionInputRecord>()
        private val texts = mutableMapOf<String, String>()

        override fun <T> transaction(action: () -> T): T = synchronized(this) { action() }

        override fun authorize(
            caller: SessionHistoryCaller,
            target: String,
        ): Long {
            require(active && allowed && caller.sessionId == authorizedSource && target != caller.sessionId)
            return revision
        }

        override fun receipt(
            source: String,
            key: String,
        ) = rows[source to key]

        override fun text(record: SessionInputRecord) = texts.getValue(record.inputId)

        override fun accept(spec: SessionInputSpec): SessionInputAcceptResult {
            val failure =
                when {
                    !targetAllowed -> "SESSION_DELEGATION_CYCLE"
                    rejection != null -> rejection
                    spec.delivery == SessionInputDelivery.STEER && !targetLive -> "INPUT_TARGET_UNAVAILABLE"
                    else -> null
                }
            if (failure != null) return SessionInputAcceptResult.Rejected(failure)
            accepts++
            val record =
                SessionInputRecord(
                    schemaVersion = 1,
                    inputId = spec.inputId,
                    sessionId = spec.sessionId,
                    sequence = 1,
                    delivery = spec.delivery,
                    expectedTurnId = spec.expectedTurnId,
                    revision = spec.revision,
                    textRef = "test-content",
                    textBytes =
                        spec.text
                            .toByteArray()
                            .size
                            .toLong(),
                    attachments = spec.attachments,
                    configuration = spec.configuration,
                    state = SessionInputState.PENDING,
                    consumedTurnId = null,
                    messageId = null,
                    requestModelCallId = null,
                    blockedReason = null,
                    createdAt = spec.createdAt,
                    updatedAt = spec.createdAt,
                    delegation = spec.delegation,
                )
            val origin = requireNotNull(spec.delegation)
            rows[origin.sourceSessionId to origin.requestKey] = record
            texts[record.inputId] = spec.text
            return SessionInputAcceptResult.Accepted(record, false)
        }

        override fun turnState(turnId: String) = targetTurnState
    }
}
