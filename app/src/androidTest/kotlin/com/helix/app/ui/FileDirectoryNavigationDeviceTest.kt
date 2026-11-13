package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Owned private directory and ordinary button path; no SAF grant, model or external effects. */
class FileDirectoryNavigationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directoryButtonNavigatesToTheBoundDraftAndCanBeUsedAgain() =
        withDirectory { container, path ->
            val chat = container.chatService
            val opened = CopyOnWriteArrayList<String>()
            val state =
                FilesScreenState(container.fileManager).apply {
                    sources = container.fileManager.sources()
                    selectedScopeId = path.scopeId
                    currentPath = path.relativePath
                }
            compose.setContent {
                MaterialTheme {
                    FileDirectoryTask(state, FileConversationHandoff(chat) { opened += it })
                }
            }
            repeat(2) { index ->
                compose.onNodeWithTag("files-directory-task").performClick()
                compose.waitUntil(10_000) { opened.size == index + 1 }
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == opened.last() }
                assertEquals(path.toModelReference(), chat.screen.value.directoryRef)
                assertTrue(chat.screen.value.isDraft)
                assertNull(chat.screen.value.activeTurn)
            }
            assertNotEquals(opened[0], opened[1])
        }

    @Test fun targetAuthorityAcceptsNewDraftWithoutWaitingForPresentationAndRejectsReplacement() =
        withDirectory { container, _ ->
            val chat = container.chatService
            compose.runOnIdle {
                val oldProjection = chat.screen.value
                val created = requireNotNull(chat.newSessionDraft())
                // Keep the pre-creation projection deliberately stale. Navigation must not consult it.
                assertNotEquals(created, oldProjection.openSessionId)
                val staleProjectionGuard = NavigationCompletion({ oldProjection.openSessionId == created })
                assertFalse(
                    staleProjectionGuard.complete { error("The stale projection cannot authorize this target") },
                )
                assertTrue(chat.isCurrentConversation(created))
                var opened = 0
                val valid = NavigationCompletion({ chat.isCurrentConversation(created) })
                assertTrue(valid.complete { opened++ })
                assertFalse(valid.complete { opened++ })
                val late = NavigationCompletion({ chat.isCurrentConversation(created) })
                val replacement = requireNotNull(chat.newSessionDraft())
                assertFalse(late.complete { opened++ })
                assertTrue(chat.isCurrentConversation(replacement))
                val departed = NavigationCompletion({ chat.isCurrentConversation(replacement) })
                departed.invalidate()
                assertFalse(departed.complete { opened++ })
                assertEquals(1, opened)
            }
        }

    @Test fun supersededDirectoryRequestDoesNotReplaceTheNewConversation() =
        withDirectory { container, path ->
            val chat = container.chatService
            var original = ""
            var replacement = ""
            compose.runOnIdle {
                original = requireNotNull(chat.newSessionDraft())
                replacement = requireNotNull(chat.newSessionDraft())
            }
            assertNull(runBlocking { chat.newDirectoryDraft(path.toModelReference(), original) })
            assertTrue(chat.isCurrentConversation(replacement))
        }

    private fun withDirectory(checkCase: (AppContainer, FileScopePath) -> Unit): Unit =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            check(app.packageName.endsWith(".asrvalidation"))
            val container = app.awaitAppContainer()
            val chat = container.chatService
            val previous = chat.screen.value
            val owner = "directory-navigation-${UUID.randomUUID()}"
            container.storage.sessions.create(owner, "Directory navigation fixture", null, null, 1)
            val path = FileScopePath.fromModelReference(container.storage.workspaces.defaultDirectory(owner, 1))
            try {
                var initial: String? = null
                compose.runOnIdle { initial = chat.newSessionDraft() }
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == initial }
                checkCase(container, path)
                assertTrue(
                    container.storage.turns
                        .listBySession(owner)
                        .isEmpty(),
                )
            } finally {
                compose.runOnIdle {
                    if (previous.isDraft) {
                        chat.newSessionDraft()
                    } else {
                        previous.openSessionId?.let(chat::openSession) ?: chat.closeSession()
                    }
                }
                container.storage.sessions.archive(owner, System.currentTimeMillis())
            }
        }
}
