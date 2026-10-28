package com.helix.app.reminder

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.core.storage.reminder.LocalReminder
import com.helix.core.storage.reminder.ReminderEvent
import com.helix.core.storage.reminder.ReminderStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ReminderInterpretUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun naturalLanguageEntryWithoutAChosenModelCannotSendOrSchedule() =
        fixture(
            { service -> ReminderScreen(service) },
        ) { _, store, delivery ->
            compose.onNodeWithTag("reminder-natural-language").performClick()
            compose.onNodeWithTag("reminder-nl-description").performTextReplacement("Synthetic 明天下午三点喝水")
            compose.onNodeWithTag("reminder-nl-prepare").assertIsNotEnabled()
            assertTrue(runBlocking { store.dao.all().isEmpty() })
            assertEquals(0, delivery.scheduled)
        }

    @Test fun modelProposalCanBeEditedAndOnlySaveSchedulesIt() =
        fixture(
            { service -> ReminderEditor(service, proposal()) {} },
        ) { _, store, delivery ->
            compose.onNodeWithTag("reminder-nl-draft").assertExists()
            assertTrue(runBlocking { store.dao.all().isEmpty() })
            assertEquals(0, delivery.scheduled)
            compose.onNodeWithTag("reminder-title").performTextReplacement("Owner corrected synthetic title")
            compose.onNodeWithTag("reminder-editor").performScrollToNode(hasTestTag("reminder-save"))
            compose.onNodeWithTag("reminder-save").performClick()
            compose.waitUntil(5000) { runBlocking { store.dao.all().size == 1 } }
            val saved = runBlocking { store.dao.all().single() }
            assertEquals("Owner corrected synthetic title", saved.title)
            assertEquals("DAILY", saved.repeat)
            assertEquals("UTC", saved.zone)
            assertTrue(saved.id.isNotBlank())
            assertEquals(1, delivery.scheduled)
        }

    @Test fun discardingAProposalDoesNotCreateAnything() {
        val closed = AtomicBoolean(false)
        fixture({ service -> ReminderEditor(service, proposal()) { closed.set(true) } }) { _, store, delivery ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose.onNodeWithText(context.getString(R.string.reminder_back_discard)).performClick()
            assertTrue(closed.get())
            assertTrue(runBlocking { store.dao.all().isEmpty() })
            assertEquals(0, delivery.scheduled)
        }
    }

    @Test fun retryAfterProjectionFailureRetainsOneDurableIdentity() =
        fixture(
            { service -> ReminderEditor(service, proposal()) {} },
        ) { _, store, delivery ->
            delivery.failOnce = true
            compose.onNodeWithTag("reminder-editor").performScrollToNode(hasTestTag("reminder-save"))
            compose.onNodeWithTag("reminder-save").performClick()
            compose.waitUntil(5000) { runBlocking { store.dao.all().size == 1 } && delivery.scheduled == 1 }
            val id =
                runBlocking {
                    store.dao
                        .all()
                        .single()
                        .id
                }
            val error =
                InstrumentationRegistry.getInstrumentation().targetContext.getString(
                    R.string.reminder_saved_projection_failed,
                )
            compose.waitUntil(5000) {
                compose.onAllNodes(hasText(error)).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(hasTestTag("reminder-save") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("reminder-editor").performScrollToNode(hasTestTag("reminder-nl-draft"))
            compose.onNodeWithTag("reminder-nl-draft").assertTextEquals(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.reminder_nl_save_check),
            )
            compose.onNodeWithTag("reminder-editor").performScrollToNode(hasTestTag("reminder-save"))
            compose.onNodeWithTag("reminder-save").performClick()
            compose.waitUntil(5000) {
                delivery.scheduled == 2 &&
                    runBlocking {
                        store.dao
                            .all()
                            .single()
                            .scheduling == "APPROXIMATE"
                    }
            }
            assertEquals(1, runBlocking { store.dao.all().size })
            assertEquals(
                id,
                runBlocking {
                    store.dao
                        .all()
                        .single()
                        .id
                },
            )
            assertEquals(
                "APPROXIMATE",
                runBlocking {
                    store.dao
                        .all()
                        .single()
                        .scheduling
                },
            )
        }

    private fun proposal() =
        LocalReminder("", "Synthetic proposal", "Not saved", "2030-01-02T09:00", "UTC", "DAILY", false, null)

    private class Delivery : ReminderDelivery {
        @Volatile var scheduled = 0
        var failOnce = false

        override fun schedule(item: LocalReminder): String {
            scheduled++
            if (failOnce) {
                failOnce = false
                error("Synthetic projection failure")
            }
            return "APPROXIMATE"
        }

        override fun cancel(item: LocalReminder) = Unit

        override fun post(item: LocalReminder) = error("No notification allowed")

        override fun posted(event: ReminderEvent) = false

        override fun dismiss(id: String) = Unit
    }

    private fun fixture(
        content: @Composable (ReminderService) -> Unit,
        block: (ReminderService, ReminderStore, Delivery) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "reminder-nl-ui-${UUID.randomUUID()}.db"
        val store = ReminderStore(context, name)
        val delivery = Delivery()
        val service = ReminderService(store, delivery)
        val visible = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { if (visible.value) MaterialTheme { content(service) } }
        try {
            block(service, store, delivery)
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            service.close()
            store.close()
            context.deleteDatabase(name)
        }
    }
}
