package com.helix.app.reminder

import com.helix.core.storage.reminder.LocalReminder
import com.helix.core.storage.reminder.ReminderDao
import com.helix.core.storage.reminder.ReminderEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderServiceTest {
    @Test fun committedFailureCanRetryUsingReturnedRevision() =
        runBlocking {
            fixture { service, dao, delivery ->
                delivery.fail = "schedule"
                val committed = projectionFailure { service.save(draft()) }.committed
                assertEquals(dao.row, committed)
                assertEquals(1L, committed.revision)
                assertEquals("PENDING", committed.scheduling)
                delivery.fail = ""
                service.save(committed.copy(body = "Retained editor content"))
                assertEquals(2L, dao.row!!.revision)
                assertEquals("Retained editor content", dao.row!!.body)
                assertEquals("APPROXIMATE", dao.row!!.scheduling)
            }
        }

    @Test fun existingEditorRetryDoesNotOverwriteInterveningChange() =
        runBlocking {
            fixture { service, dao, delivery ->
                service.save(draft())
                val original = dao.row!!
                delivery.fail = "dismiss"
                val committed = projectionFailure { service.save(original.copy(body = "Editor")) }.committed
                assertEquals(2L, committed.revision)
                delivery.fail = ""
                service.save(committed.copy(body = "Other editor"))
                val newer = dao.row
                rejected { service.save(committed.copy(body = "Retry")) }
                assertEquals(newer, dao.row)
            }
        }

    @Test fun originalRevisionRetryIsRejectedAfterCommit() =
        runBlocking {
            fixture { service, dao, delivery ->
                service.save(draft())
                val original = dao.row!!
                delivery.fail = "cancel"
                projectionFailure { service.save(original.copy(title = "Saved")) }
                delivery.fail = ""
                rejected { service.save(original.copy(title = "Stale")) }
                assertEquals("Saved", dao.row!!.title)
            }
        }

    @Test fun staleFinishAndCancelDoNotTouchProjections() =
        runBlocking {
            fixture { service, dao, delivery ->
                service.save(draft())
                val original = dao.row!!
                service.save(original.copy(body = "Newer"))
                val newer = dao.row
                val calls = delivery.calls
                rejected { service.finish(original.id, false, original.revision) }
                rejected { service.finish(original.id, true, original.revision) }
                assertFalse(service.cancelRevision(original.id, original.revision))
                assertEquals(newer, dao.row)
                assertEquals(calls, delivery.calls)
            }
        }

    @Test fun finishProjectionFailureReportsTerminalCommitAndRejectsStaleRetry() =
        runBlocking {
            for (cancelled in listOf(false, true)) {
                fixture { service, dao, delivery ->
                    service.save(draft())
                    val original = dao.row!!
                    delivery.fail = "cancel"
                    val committed =
                        projectionFailure {
                            service.finish(original.id, cancelled, original.revision)
                        }.committed
                    assertEquals(if (cancelled) "CANCELLED" else "COMPLETED", committed.state)
                    assertEquals(dao.row, committed)
                    assertNull(committed.nextAt)
                    delivery.fail = ""
                    rejected { service.finish(original.id, cancelled, original.revision) }
                    assertFalse(service.cancelRevision(original.id, committed.revision))
                    service.reconcile()
                    assertEquals(committed, dao.row)
                }
            }
        }

    @Test fun ordinaryPermissionDowngradeIsSuccessful() =
        runBlocking {
            fixture { service, dao, delivery ->
                delivery.status = "NOTIFICATIONS_OFF"
                assertEquals("one", service.save(draft().copy(exact = true)))
                assertEquals("NOTIFICATIONS_OFF", dao.row!!.scheduling)
                assertFalse(service.failure.value)
            }
        }

    @Test fun projectionCancellationDoesNotPretendTheCommitRolledBack() =
        runBlocking {
            fixture { service, dao, delivery ->
                delivery.cancelScheduling = true
                try {
                    service.save(draft())
                    error("Expected cancellation")
                } catch (_: kotlinx.coroutines.CancellationException) {
                    assertEquals(1L, dao.row!!.revision)
                    assertEquals("PENDING", dao.row!!.scheduling)
                }
                delivery.cancelScheduling = false
                service.reconcile()
                assertEquals(1L, dao.row!!.revision)
                assertEquals("APPROXIMATE", dao.row!!.scheduling)
            }
        }

    @Test fun preCommitFailureHasNoProjectionOrCommittedOutcome() =
        runBlocking {
            fixture { service, dao, delivery ->
                dao.failSave = true
                rejected { service.save(draft()) }
                assertNull(dao.row)
                assertEquals(0, delivery.calls)
            }
        }

    @Test fun cancelledSavePublishesItsCommitBeforeReturningAndRestoredEditorCanRetry() =
        runBlocking {
            withTimeout(5000) {
                fixture { service, dao, delivery ->
                    service.save(draft())
                    val original = dao.row!!
                    val page = ReminderPageState().apply { openEditor(original) }
                    val written = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    dao.afterSave = {
                        written.complete(Unit)
                        release.await()
                    }
                    val calls = delivery.calls
                    var returned = false
                    val operation =
                        launch {
                            service.save(original.copy(body = "Retained draft"), page.receipt)
                            returned = true
                        }
                    try {
                        written.await()
                        operation.cancel()
                    } finally {
                        release.complete(Unit)
                    }
                    operation.join()
                    assertFalse(returned)
                    assertEquals(calls, delivery.calls)
                    // Recreated composition still receives the same page-owned receipt, not the old selected row.
                    val restored = page.receipt.committed.value!!
                    assertEquals(original, page.selected)
                    assertEquals(2L, restored.revision)
                    assertEquals("Retained draft", restored.body)
                    assertEquals(dao.row, restored)
                    dao.afterSave = {}
                    service.reconcile()
                    service.save(restored.copy(body = "Retry retained draft"), page.receipt)
                    assertEquals(3L, dao.row!!.revision)
                    assertEquals("Retry retained draft", dao.row!!.body)
                }
            }
        }

    @Test fun cancelledFinishRetainsTerminalStateForRestoredEditor() =
        runBlocking {
            withTimeout(5000) {
                for (cancelled in listOf(false, true)) {
                    fixture { service, dao, delivery ->
                        service.save(draft())
                        val original = dao.row!!
                        val page = ReminderPageState().apply { openEditor(original) }
                        val written = CompletableDeferred<Unit>()
                        val release = CompletableDeferred<Unit>()
                        dao.afterSave = {
                            written.complete(Unit)
                            release.await()
                        }
                        val calls = delivery.calls
                        val operation =
                            launch {
                                service.finish(original.id, cancelled, original.revision, page.receipt)
                                error("Cancelled caller must not receive success")
                            }
                        try {
                            written.await()
                            operation.cancel()
                        } finally {
                            release.complete(Unit)
                        }
                        operation.join()
                        val restored = page.receipt.committed.value!!
                        assertEquals(if (cancelled) "CANCELLED" else "COMPLETED", restored.state)
                        assertEquals(2L, restored.revision)
                        assertEquals(dao.row, restored)
                        assertEquals(calls, delivery.calls)
                        dao.afterSave = {}
                        rejected { service.finish(original.id, cancelled, original.revision, page.receipt) }
                        service.reconcile()
                        assertEquals(restored, dao.row)
                    }
                }
            }
        }

    @Test fun cancellationDuringProjectionRetainsReceiptWithoutAdoptingAnotherEditorsRevision() =
        runBlocking {
            fixture { service, dao, delivery ->
                service.save(draft())
                val original = dao.row!!
                val page = ReminderPageState().apply { openEditor(original) }
                delivery.cancelScheduling = true
                try {
                    service.save(original.copy(body = "Own change"), page.receipt)
                    error("Expected cancellation")
                } catch (_: kotlinx.coroutines.CancellationException) {
                    assertEquals(
                        2L,
                        page.receipt.committed.value!!
                            .revision,
                    )
                }
                delivery.cancelScheduling = false
                val ownCommit = page.receipt.committed.value!!
                service.save(ownCommit.copy(body = "Other editor"))
                val newer = dao.row
                rejected { service.save(ownCommit.copy(body = "Retained retry"), page.receipt) }
                assertEquals(newer, dao.row)
                assertEquals(ownCommit, page.receipt.committed.value)
            }
        }

    @Test fun cancellationAfterProjectionBeforeReturnStillPublishesReceipt() =
        runBlocking {
            fixture { service, dao, delivery ->
                val page = ReminderPageState().apply { openEditor(null) }
                val operation =
                    launch(start = CoroutineStart.LAZY) {
                        service.save(draft(), page.receipt)
                        error("Cancelled return must not appear successful")
                    }
                delivery.afterSchedule = { operation.cancel() }
                operation.start()
                operation.join()
                assertEquals(
                    1L,
                    page.receipt.committed.value!!
                        .revision,
                )
                assertEquals("APPROXIMATE", dao.row!!.scheduling)
                assertEquals(
                    "PENDING",
                    page.receipt.committed.value!!
                        .scheduling,
                )
                delivery.afterSchedule = {}
                val committed = page.receipt.committed.value!!
                service.save(draft().copy(revision = committed.revision), page.receipt)
                assertEquals(2L, dao.row!!.revision)
                assertEquals(committed.id, dao.row!!.id)
            }
        }

    @Test fun cancellationBeforeCommitDoesNotPublishOrWrite() =
        runBlocking {
            fixture { service, dao, delivery ->
                val receipt = ReminderService.CommitReceipt()
                dao.afterGet = { currentCoroutineContext().cancel() }
                val operation =
                    launch {
                        service.save(draft(), receipt)
                        error("Cancelled pre-commit caller must not receive success")
                    }
                operation.join()
                assertNull(dao.row)
                assertNull(receipt.committed.value)
                assertEquals(0, delivery.calls)
            }
        }

    @Test fun failedWriteDoesNotPublishReceiptAndExplicitReopenStartsANewEditor() =
        runBlocking {
            fixture { service, dao, _ ->
                val page = ReminderPageState().apply { openEditor(null) }
                dao.failSave = true
                rejected { service.save(draft(), page.receipt) }
                assertNull(page.receipt.committed.value)
                dao.failSave = false
                service.save(draft(), page.receipt)
                val oldReceipt = page.receipt
                page.openEditor(dao.row)
                assertNull(page.receipt.committed.value)
                assertEquals(dao.row, oldReceipt.committed.value!!.copy(scheduling = "APPROXIMATE"))
                assertEquals(dao.row, page.selected)
            }
        }

    private suspend fun projectionFailure(block: suspend () -> Unit): ReminderService.ProjectionFailure {
        try {
            block()
        } catch (failure: ReminderService.ProjectionFailure) {
            return failure
        }
        error("Expected committed projection failure")
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: IllegalStateException) {
            assertFalse(failure is ReminderService.ProjectionFailure)
            return
        }
        error("Expected revision or persistence rejection")
    }

    private suspend fun fixture(block: suspend (ReminderService, Dao, Delivery) -> Unit) {
        val dao = Dao()
        val delivery = Delivery()
        val service = ReminderService(dao, delivery, { 1L }, { it() })
        try {
            block(service, dao, delivery)
        } finally {
            service.close()
        }
    }

    private fun draft() = LocalReminder("one", "Title", "Body", "2030-01-01T09:00", "UTC", "ONCE", false, null)

    private class Delivery : ReminderDelivery {
        var fail = ""
        var status = "APPROXIMATE"
        var calls = 0
        var cancelScheduling = false
        var afterSchedule: () -> Unit = {}

        private fun invoke(operation: String) {
            calls++
            check(fail != operation) { "Synthetic $operation failure" }
        }

        override fun schedule(item: LocalReminder): String {
            if (cancelScheduling) throw kotlinx.coroutines.CancellationException("Synthetic cancellation")
            invoke("schedule")
            afterSchedule()
            return status
        }

        override fun cancel(item: LocalReminder) = invoke("cancel")

        override fun dismiss(id: String) = invoke("dismiss")

        override fun post(item: LocalReminder): String = error("Not expected")

        override fun posted(event: ReminderEvent) = false
    }

    private class Dao : ReminderDao {
        var row: LocalReminder? = null
        var failSave = false
        var afterSave: suspend () -> Unit = {}
        var afterGet: suspend () -> Unit = {}

        override fun observe() = flowOf(emptyList<LocalReminder>())

        override suspend fun get(id: String): LocalReminder? {
            val item = row?.takeIf { it.id == id }
            afterGet()
            return item
        }

        override suspend fun all() = listOfNotNull(row)

        override suspend fun save(item: LocalReminder) {
            check(!failSave)
            row = item
            afterSave()
        }

        override suspend fun delete(id: String) {
            row = null
        }

        override suspend fun deleteEvents(id: String) = Unit

        override suspend fun event(event: ReminderEvent) = Unit

        override suspend fun events(id: String) = emptyList<ReminderEvent>()

        override suspend fun eventAt(
            id: String,
            revision: Long,
            due: Long,
        ): ReminderEvent? = null

        override suspend fun uncertain() = emptyList<ReminderEvent>()
    }
}
