package com.helix.app.reminder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.storage.reminder.LocalReminder
import com.helix.core.storage.reminder.ReminderEvent
import com.helix.core.storage.reminder.ReminderStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Compiling this fixture is host verification; execution requires explicit device authorization. */
class ReminderCommitRecoveryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun committedSaveSurvivesCompositionCancellationAndRetriesWithRetainedDraft() =
        fixture { service, page, delivery, restoration, backCalls ->
            compose.onNodeWithTag("reminder-body").performTextReplacement("Retained editor draft")
            scroll("reminder-save")
            delivery.holdNextCancel()
            compose.onNodeWithTag("reminder-save").performClick()
            delivery.awaitCommit()
            restoration.emulateSavedInstanceStateRestore()
            delivery.release.countDown()
            compose.onNodeWithTag("reminder-body").assertTextContains("Retained editor draft", substring = true)
            scroll("reminder-save")
            compose.onNodeWithTag("reminder-save").performClick()
            compose.waitUntil(5000) {
                runBlocking { service.get(page.selected!!.id)?.revision == 3L } && backCalls.get() == 1
            }
            val saved = runBlocking { service.get(page.selected!!.id) }!!
            assertEquals("Retained editor draft", saved.body)
            assertEquals("ACTIVE", saved.state)
        }

    @Test fun committedCompletionSurvivesCompositionCancellationWithoutOldActions() = terminalRecovery(false)

    @Test fun committedCancellationSurvivesCompositionCancellationWithoutOldActions() = terminalRecovery(true)

    private fun terminalRecovery(cancelled: Boolean) =
        fixture { service, page, delivery, restoration, backCalls ->
            val tag = if (cancelled) "reminder-cancel" else "reminder-complete"
            scroll(tag)
            delivery.holdNextCancel()
            compose.onNodeWithTag(tag).performClick()
            delivery.awaitCommit()
            restoration.emulateSavedInstanceStateRestore()
            delivery.release.countDown()
            scroll("reminder-save")
            compose.onNodeWithTag("reminder-save").assertIsNotEnabled()
            compose.onNodeWithTag("reminder-complete").assertDoesNotExist()
            compose.onNodeWithTag("reminder-cancel").assertDoesNotExist()
            val saved = runBlocking { service.get(page.selected!!.id) }!!
            assertEquals(if (cancelled) "CANCELLED" else "COMPLETED", saved.state)
            assertEquals(2L, saved.revision)
            assertEquals(0, backCalls.get())
        }

    private fun scroll(tag: String) {
        compose.onNodeWithTag("reminder-editor").performScrollToNode(hasTestTag(tag))
    }

    private fun fixture(
        block: (ReminderService, ReminderPageState, Delivery, StateRestorationTester, AtomicInteger) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "reminder-commit-recovery-${UUID.randomUUID()}.db"
        val store = ReminderStore(context, name)
        val delivery = Delivery()
        val service = ReminderService(store, delivery)
        val visible = androidx.compose.runtime.mutableStateOf(true)
        val backCalls = AtomicInteger()
        try {
            runBlocking {
                service.save(LocalReminder("one", "Synthetic recovery", "Original", "", "UTC", "ONCE", false, null))
            }
            val page = ReminderPageState().apply { openEditor(runBlocking { service.get("one") }) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                if (visible.value) {
                    MaterialTheme {
                        ReminderEditor(service, page.selected, page.receipt) { backCalls.incrementAndGet() }
                    }
                }
            }
            block(service, page, delivery, restoration, backCalls)
        } finally {
            delivery.release.countDown()
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            runBlocking { service.reconcile() }
            service.close()
            store.close()
            context.deleteDatabase(name)
        }
    }

    private class Delivery : ReminderDelivery {
        private val gate = AtomicReference<CountDownLatch?>()
        private val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun holdNextCancel() {
            gate.set(release)
        }

        fun awaitCommit() {
            assertTrue("Committed operation did not reach projection", entered.await(5, TimeUnit.SECONDS))
        }

        override fun cancel(item: LocalReminder) {
            gate.getAndSet(null)?.let {
                entered.countDown()
                check(it.await(5, TimeUnit.SECONDS)) { "Synthetic projection was not released" }
            }
        }

        override fun schedule(item: LocalReminder) = "APPROXIMATE"

        override fun dismiss(id: String) = Unit

        override fun post(item: LocalReminder): String = error("No notification allowed")

        override fun posted(event: ReminderEvent) = false
    }
}
