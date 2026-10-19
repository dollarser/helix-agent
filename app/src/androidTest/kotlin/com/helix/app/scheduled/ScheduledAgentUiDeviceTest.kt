package com.helix.app.scheduled

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.reminder.ReminderDelivery
import com.helix.app.reminder.ReminderScreen
import com.helix.app.reminder.ReminderService
import com.helix.core.storage.reminder.LocalReminder
import com.helix.core.storage.reminder.ReminderEvent
import com.helix.core.storage.reminder.ReminderStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ScheduledAgentUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitSessionAndReviewAreRequiredBeforeEnabling() {
        ScheduledFixture().use { f ->
            val saved = AtomicInteger()
            compose.setContent { MaterialTheme { ScheduledAgentEditor(f.service, null) { saved.incrementAndGet() } } }
            compose.onNodeWithTag("scheduled-editor").performScrollToNode(hasTestTag("scheduled-review"))
            compose.onNodeWithTag("scheduled-review").assertIsNotEnabled()
            compose.onNodeWithTag("scheduled-editor").performScrollToNode(hasTestTag("scheduled-name"))
            compose.onNodeWithTag("scheduled-name").performTextReplacement("Synthetic scheduled task")
            compose.onNodeWithTag("scheduled-prompt").performTextReplacement("Summarize synthetic facts only.")
            compose.waitUntil(
                5000,
            ) { compose.onAllNodes(hasTestTag("scheduled-session-bound")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("scheduled-session-bound").performClick()
            compose.onNodeWithTag("scheduled-editor").performScrollToNode(hasTestTag("scheduled-enabled"))
            compose.onNodeWithTag("scheduled-enabled").performClick()
            compose.onNodeWithTag("scheduled-editor").performScrollToNode(hasTestTag("scheduled-review"))
            compose.onNodeWithTag("scheduled-review").performClick()
            compose.waitUntil(
                5000,
            ) { compose.onAllNodes(hasTestTag("scheduled-confirm")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(
                runBlocking {
                    f.store.dao
                        .rules()
                        .isEmpty()
                },
            )
            assertTrue(f.execution.submitted.isEmpty())
            assertTrue(f.wake.scheduled.isEmpty())
            compose.onNodeWithTag("scheduled-confirm").performClick()
            compose.waitUntil(5000) { saved.get() == 1 }
            val rule =
                runBlocking {
                    f.store.dao
                        .rules()
                        .single()
                }
            assertEquals("bound", rule.sessionId)
            assertTrue(rule.enabled)
            assertEquals(1, f.wake.scheduled.size)
            assertTrue(f.execution.submitted.isEmpty())
        }
    }

    @Test fun scopeDriftAfterPreviewCannotSaveOrExecute() {
        ScheduledFixture().use { f ->
            val draft = f.draft(enabled = true)
            compose.setContent { MaterialTheme { ScheduledAgentEditor(f.service, draft) {} } }
            compose.onNodeWithTag("scheduled-editor").performScrollToNode(hasTestTag("scheduled-review"))
            compose.onNodeWithTag("scheduled-review").performClick()
            compose.waitUntil(
                5000,
            ) { compose.onAllNodes(hasTestTag("scheduled-confirm")).fetchSemanticsNodes().isNotEmpty() }
            f.execution.hash = "changed-after-confirmation"
            compose.onNodeWithTag("scheduled-confirm").performClick()
            compose.waitUntil(
                5000,
            ) { compose.onAllNodes(hasTestTag("scheduled-confirm")).fetchSemanticsNodes().isEmpty() }
            assertTrue(
                runBlocking {
                    f.store.dao
                        .rules()
                        .isEmpty()
                },
            )
            assertTrue(f.execution.submitted.isEmpty())
            assertTrue(f.wake.scheduled.isEmpty())
        }
    }

    @Test fun openingReminderFromScheduledPageShowsTheRequestedItem() {
        ScheduledFixture().use { f ->
            val context =
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext
            val name = "scheduled-reminder-ui-${java.util.UUID.randomUUID()}.db"
            val store = ReminderStore(context, name)
            val delivery =
                object : ReminderDelivery {
                    override fun schedule(item: LocalReminder) = error("No alarm allowed")

                    override fun cancel(item: LocalReminder) = Unit

                    override fun post(item: LocalReminder) = error("No notification allowed")

                    override fun posted(event: ReminderEvent) = false

                    override fun dismiss(id: String) = Unit
                }
            val reminders = ReminderService(store, delivery)
            val memo =
                LocalReminder(
                    "synthetic-memo",
                    "Synthetic memo",
                    "Text",
                    "",
                    "UTC",
                    "ONCE",
                    false,
                    null,
                )
            runBlocking { reminders.save(memo) }
            val visible = androidx.compose.runtime.mutableStateOf(true)
            compose.setContent {
                if (visible.value) {
                    MaterialTheme {
                        ReminderScreen(reminders, scheduled = f.service)
                    }
                }
            }
            try {
                compose.onNodeWithTag("scheduled-entry").performClick()
                compose.onNodeWithTag("scheduled-list").assertExists()
                reminders.openRequest.value = memo.id
                compose.waitUntil(5000) {
                    compose.onAllNodes(hasTestTag("reminder-editor")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("reminder-title").assertExists()
                assertTrue(f.execution.submitted.isEmpty())
            } finally {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
                reminders.close()
                store.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test fun longRetryPromptCanScrollToOriginalModelTargetAndCancel() {
        ScheduledFixture().use { f ->
            f.execution.completion = "FAILED"
            val prompt = (1..150).joinToString("\n") { "Synthetic long task line $it" }
            val rule =
                runBlocking {
                    val saved = f.save(f.draft().copy(prompt = prompt))
                    f.fire(saved)
                    saved
                }
            val visible = androidx.compose.runtime.mutableStateOf(true)
            compose.setContent {
                if (visible.value) MaterialTheme { ScheduledAgentScreen(f.service, {}, {}) }
            }
            try {
                val tag = "scheduled-retry-${rule.id}:${rule.revision}:${rule.nextAt}"
                compose.waitUntil(5000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("scheduled-list").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).performClick()
                compose.onNodeWithTag("scheduled-retry-preview").assertExists()
                compose.onNodeWithTag("scheduled-scope-target").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("scheduled-retry-cancel").performClick()
                assertEquals(1, f.execution.submitted.size)
            } finally {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
            }
        }
    }
}
