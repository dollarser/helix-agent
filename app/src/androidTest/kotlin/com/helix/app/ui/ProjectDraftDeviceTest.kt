package com.helix.app.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso
import com.helix.app.MainActivity
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Real UI and Room, synthetic local files only; no model requests or device authorization changes. */
class ProjectDraftDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod") // One owned conversation crosses picker, cache, membership and recreation.
    fun pickerPreservesInputAndAttachmentAcrossCancelSelectionCreationAndRecreation() =
        runBlocking<Unit> {
            compose.resetDeterministicUiState()
            val c = compose.container()
            val chat = c.chatService
            val projects = requireNotNull(c.projects)
            val project = projects.save(null, "Choose-${UUID.randomUUID()}", "", "", null)
            val owned = mutableListOf(project)
            val file = compose.activity.filesDir.resolve("workspaces/app/work/project-draft-${UUID.randomUUID()}.txt")
            file.parentFile?.mkdirs()
            file.writeText("owned attachment")
            var id: String? = null
            try {
                compose.runOnIdle { id = chat.newSessionDraft() }
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == id }
                val session = requireNotNull(id)
                compose.onNodeWithTag("chat-input").performTextReplacement("Keep this unsent question")
                Espresso.closeSoftKeyboard()
                val authority = "${compose.activity.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(compose.activity, authority, file)
                chat.stageAttachment(uri.toString(), session)
                compose.waitUntil(10_000) {
                    chat.screen.value.pendingAttachments
                        .isNotEmpty()
                }
                compose.onNodeWithTag("conversation-project").performClick()
                compose.onNodeWithTag("conversation-project-create").performClick()
                compose.onNodeWithTag("project-name").performTextReplacement("Cancelled project")
                Espresso.closeSoftKeyboard()
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation()
                    .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("project-name").fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithText(compose.activity.getString(com.helix.app.R.string.common_cancel)).performClick()
                compose.onNodeWithTag("chat-input").assertTextContains("Keep this unsent question")
                assertEquals(session, chat.screen.value.openSessionId)
                assertNull(c.storage.projects.forSession(session))
                compose.onNodeWithTag("conversation-project").performClick()
                compose.onNodeWithTag("conversation-project-$project").performClick()
                compose.waitUntil(10_000) { chat.screen.value.projectId == project }
                val directory = chat.screen.value.directoryRef
                val attachment =
                    chat.screen.value.pendingAttachments
                        .single()
                        .id
                assertEquals(session, chat.screen.value.openSessionId)
                assertTrue(chat.selectConversationProject(session, project))
                assertEquals(directory, chat.screen.value.directoryRef)
                assertEquals(
                    attachment,
                    chat.screen.value.pendingAttachments
                        .single()
                        .id,
                )
                compose.onNodeWithTag("chat-input").assertTextContains("Keep this unsent question")
                compose.onNodeWithTag("conversation-project").performClick()
                compose.onNodeWithTag("conversation-project-create").performClick()
                val name = "Inline-${UUID.randomUUID()}"
                compose.onNodeWithTag("project-name").performTextReplacement(name)
                Espresso.closeSoftKeyboard()
                compose.onNodeWithTag("project-save").performClick()
                compose.waitUntil(10_000) {
                    chat.screen.value.projectId != project && chat.screen.value.projectId != null
                }
                val created = requireNotNull(chat.screen.value.projectId)
                owned += created
                assertEquals(
                    name,
                    c.storage.projects
                        .find(created)
                        ?.name,
                )
                compose.waitUntil(10_000) {
                    c.storage.composerDrafts
                        .get(session)
                        ?.text == "Keep this unsent question"
                }
                compose.activityRule.scenario.recreate()
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == session }
                compose.onNodeWithTag("chat-input").assertTextContains("Keep this unsent question")
                assertEquals(
                    created,
                    c.storage.projects
                        .forSession(session)
                        ?.id,
                )
                assertEquals(1, chat.screen.value.pendingAttachments.size)
                assertTrue(
                    c.storage.turns
                        .listBySession(session)
                        .isEmpty(),
                )
            } finally {
                chat.closeSession()
                id?.let { c.storage.deleteSessionPermanently(it) }
                owned.forEach {
                    c.storage.projects
                        .find(it)
                        ?.let { row -> projects.delete(row) }
                }
                file.delete()
            }
        }

    @Test
    @Suppress("LongMethod") // Draft creation and failed materialization share one owned project fixture.
    fun projectEntriesKeepContextAndRejectUnavailableDirectory() =
        runBlocking<Unit> {
            compose.resetDeterministicUiState()
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            val c = compose.container()
            val chat = c.chatService
            val projects = requireNotNull(c.projects)
            val project = projects.save(null, "Context-${UUID.randomUUID()}", "", "", null)
            val record = requireNotNull(c.storage.projects.find(project))
            val reference = FileScopePath(record.workspaceId, record.relativePath).toModelReference()
            val ids = mutableListOf<String>()
            try {
                val expected = chat.screen.value.openSessionId
                val first = requireNotNull(chat.newProjectDraft(project, expected))
                ids += first
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == first && chat.screen.value.isDraft }
                assertNull(c.storage.sessions.find(first))
                assertEquals(project, chat.screen.value.projectId)
                assertEquals(reference, chat.screen.value.directoryRef)
                // Repeating an old action cannot replace the new draft.
                assertNull(chat.newProjectDraft(project, expected))
                chat.closeSession()
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == null }
                assertNull(c.storage.sessions.find(first))
                val fileName = "project-file-${UUID.randomUUID()}.txt"
                val file = compose.activity.filesDir.resolve("workspaces/app/work/$fileName")
                file.parentFile?.mkdirs()
                file.writeText("project input")
                try {
                    val authority = "${compose.activity.packageName}.fileprovider"
                    val uri = FileProvider.getUriForFile(compose.activity, authority, file)
                    var opened: String? = null
                    val handoff = FileConversationHandoff(chat, project, reference) { opened = it }
                    assertTrue(handoff.attach(uri.toString(), chat.screen.value.openSessionId, true))
                    val second = requireNotNull(opened)
                    ids += second
                    compose.waitUntil(10_000) {
                        chat.screen.value.pendingAttachments
                            .isNotEmpty()
                    }
                    assertEquals(project, chat.screen.value.projectId)
                    assertEquals(reference, chat.screen.value.directoryRef)
                    c.storage.workspaces.markUnavailable(record.workspaceId)
                    assertNull(chat.materializeDraftSession(second))
                    assertTrue(chat.screen.value.isDraft)
                    assertEquals(reference, chat.screen.value.directoryRef)
                    assertNull(c.storage.sessions.find(second))
                    assertEquals(1, chat.screen.value.pendingAttachments.size)
                    assertTrue(runCatching { chat.newProjectDraft(project, second) }.isFailure)
                    assertEquals(second, chat.screen.value.openSessionId)
                } finally {
                    file.delete()
                }
            } finally {
                chat.closeSession()
                ids.forEach {
                    c.storage.sessions
                        .find(it)
                        ?.let { _ -> c.storage.deleteSessionPermanently(it) }
                }
                c.storage.projects
                    .find(project)
                    ?.let { projects.delete(it) }
            }
        }
}
