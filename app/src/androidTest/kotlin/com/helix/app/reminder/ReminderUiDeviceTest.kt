package com.helix.app.reminder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.storage.reminder.LocalReminder
import com.helix.core.storage.reminder.ReminderEvent
import com.helix.core.storage.reminder.ReminderStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ReminderUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(block: (ReminderService) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "reminder-ui-${UUID.randomUUID()}.db"
        val store = ReminderStore(context, name)
        val delivery =
            object : ReminderDelivery {
                override fun schedule(item: LocalReminder) = "APPROXIMATE"

                override fun cancel(item: LocalReminder) = Unit

                override fun post(item: LocalReminder) = error("UI must not post directly")

                override fun posted(event: ReminderEvent) = false

                override fun dismiss(id: String) = Unit
            }
        val service = ReminderService(store, delivery)
        try {
            compose.setContent { MaterialTheme { ReminderScreen(service) } }
            block(service)
        } finally {
            service.close()
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun manualMemoCreateEditComplete() =
        fixture { service ->
            compose.onNodeWithTag("reminder-new").performClick()
            compose.onNodeWithTag("reminder-title").performTextReplacement("Synthetic 备忘")
            compose.onNodeWithTag("reminder-body").performTextReplacement("Do not send to a model")
            scroll("reminder-save")
            compose.onNodeWithTag("reminder-save").performClick()
            compose.waitUntil(5000) { runBlocking { service.items.first().isNotEmpty() } }
            val row = runBlocking { service.items.first().single() }
            compose.onNodeWithTag("reminder-${row.id}").performClick()
            compose.onNodeWithTag("reminder-body").performTextReplacement("Edited memo")
            scroll("reminder-save")
            compose.onNodeWithTag("reminder-save").performClick()
            compose.waitUntil(5000) { runBlocking { service.get(row.id)?.body == "Edited memo" } }
            compose.onNodeWithTag("reminder-${row.id}").performClick()
            scroll("reminder-complete")
            compose.onNodeWithTag("reminder-complete").performClick()
            compose.waitUntil(5000) { runBlocking { service.get(row.id)?.state == "COMPLETED" } }
        }

    @Test fun explicitCalendarReviewAndCancel() =
        fixture { service ->
            compose.onNodeWithTag("reminder-new").performClick()
            compose.onNodeWithTag("reminder-title").performTextReplacement("Synthetic weekly")
            compose.onNodeWithTag("reminder-timed").performClick()
            scroll("reminder-local")
            compose.onNodeWithTag("reminder-local").performTextReplacement("2030-01-31T09:00")
            scroll("reminder-zone")
            compose.onNodeWithTag("reminder-zone").performTextReplacement("Asia/Shanghai")
            scroll("reminder-repeat-MONTHLY")
            compose.onNodeWithTag("reminder-repeat-MONTHLY").performClick()
            assertTrue(runBlocking { service.items.first().isEmpty() })
            scroll("reminder-save")
            compose.onNodeWithTag("reminder-save").performClick()
            compose.waitUntil(5000) { runBlocking { service.items.first().isNotEmpty() } }
            val row = runBlocking { service.items.first().single() }
            assertEquals("Asia/Shanghai", row.zone)
            assertEquals("MONTHLY", row.repeat)
            compose.onNodeWithTag("reminder-${row.id}").performClick()
            scroll("reminder-cancel")
            compose.onNodeWithTag("reminder-cancel").performClick()
            compose.waitUntil(5000) { runBlocking { service.get(row.id)?.state == "CANCELLED" } }
        }

    private fun scroll(tag: String) {
        compose.onNodeWithTag("reminder-editor").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo()
    }
}
