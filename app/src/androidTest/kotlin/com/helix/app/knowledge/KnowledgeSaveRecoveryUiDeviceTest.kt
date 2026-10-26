package com.helix.app.knowledge

import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixTheme
import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class KnowledgeSaveRecoveryUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dismissKeepsDraftAndExplicitRecoveryCreatesNoteWithoutSending() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName.endsWith(".asrvalidation"))
            val name = "knowledge-save-ui-${UUID.randomUUID()}"
            val store = KnowledgeStore(context, name)
            store.saveNote(KnowledgeNote("note", "Synthetic conflict", "old", 1, 2))
            val old = store.documents.list("notes").single()
            prepareKnowledgeSave(context, name, old, "new")
            val oldUri =
                ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    old.reference.toLong(),
                )
            context.contentResolver.openOutputStream(oldUri, "rwt")!!.use {
                it.write(
                    old.bytes
                        .toString(Charsets.UTF_8)
                        .replace("\nold", "\nexternal")
                        .toByteArray(),
                )
            }
            val service = KnowledgeService(context, store = store)
            val visible = mutableStateOf(true)
            val submissions = AtomicInteger()
            try {
                service.recovery.join()
                service.refresh()
                compose.setContent {
                    if (visible.value) {
                        HelixTheme {
                            KnowledgeScreen(service) {
                                submissions.incrementAndGet()
                                false
                            }
                        }
                    }
                }
                compose.onNodeWithTag("knowledge-pending-open").performClick()
                capture("knowledge-pending-dialog.png")
                compose.onNodeWithTag("knowledge-pending-close").performClick()
                compose.onNodeWithTag("knowledge-pending-dialog").assertDoesNotExist()
                assertEquals(1, store.notes.value.size)
                assertEquals(1, service.pendingSaves.value.size)
                assertEquals(0, submissions.get())
                compose.onNodeWithTag("knowledge-pending-open").performClick()
                compose.onNodeWithTag("knowledge-recover-save").performClick()
                compose.waitUntil(15_000) { service.pendingSaves.value.isEmpty() && store.notes.value.size == 2 }
                assertEquals("external", store.note("note")!!.body)
                assertTrue(store.notes.value.any { it.body == "new" && it.kind == "NOTE" })
                capture("knowledge-note-after-recovery.png")
                assertEquals(1, store.documents.list("recovered").size)
                assertEquals(0, submissions.get())
            } finally {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
                service.close()
                clearKnowledgeDocuments(context, name)
            }
        }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Compose idling does not wait for the platform dialog window fade.
        android.os.SystemClock.sleep(400)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            instrumentation.targetContext.cacheDir.resolve(name).outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
