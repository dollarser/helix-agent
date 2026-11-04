package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.projects.ui.ProjectDetailScreen
import com.helix.app.projects.ui.ProjectsScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Real application services/Room and production UI; no provider or external account is used. */
class ProjectLifecycleUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Suppress("LongMethod") // Keep the complete UI journey and its original-session checks in one cleanup scope.
    fun createRenameAndDeletePreserveMemberSessionAndDirectory(): Unit =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            val service = requireNotNull(c.projects)
            val route = mutableStateOf<String?>(null)
            val name = "UI-${UUID.randomUUID()}"
            val openedDraft = mutableStateOf<String?>(null)
            var session: String? = null
            var project: String? = null
            try {
                compose.setContent {
                    MaterialTheme {
                        val id = route.value
                        if (id == null) {
                            ProjectsScreen(service, c.fileManager, { route.value = it })
                        } else {
                            ProjectDetailScreen(
                                c,
                                id,
                                {},
                                { route.value = null },
                                {},
                                { _, _ -> },
                                onOpenDraft = { openedDraft.value = c.chatService.screen.value.openSessionId },
                            )
                        }
                    }
                }
                compose.onNodeWithTag("project-create").assertIsDisplayed().performClick()
                compose.onNodeWithTag("project-name").performTextReplacement(name)
                compose.onNodeWithTag("project-save").performClick()
                compose.waitUntil(10_000) {
                    route.value != null &&
                        compose.onAllNodesWithTag("screen-project-detail").fetchSemanticsNodes().isNotEmpty()
                }
                val id = requireNotNull(route.value)
                project = id
                compose.onNodeWithTag("screen-project-detail").assertIsDisplayed()
                val count =
                    c.storage.sessions
                        .list()
                        .size
                compose.onNodeWithText(app.getString(com.helix.app.R.string.project_new_session)).performClick()
                compose.waitUntil(10_000) { openedDraft.value != null && c.chatService.screen.value.projectId == id }
                val draftId = requireNotNull(openedDraft.value)
                assertTrue(c.chatService.screen.value.isDraft)
                assertNull(c.storage.sessions.find(draftId))
                assertEquals(
                    count,
                    c.storage.sessions
                        .list()
                        .size,
                )
                c.chatService.closeSession()
                val member =
                    com.helix.app.projects
                        .createProjectSessionFixture(c.storage, id, null, null)
                session = member
                val directory =
                    c.storage.sessions
                        .resolve(member)
                        .directoryRef
                listOf(1 to "screen-files", 2 to "screen-tasks", 3 to "screen-artifacts").forEach { (tab, tag) ->
                    compose.onNodeWithTag("project-tab-$tab").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag(tag).assertIsDisplayed()
                }
                compose.onNodeWithTag("project-tab-0").performClick()
                compose.onNodeWithTag("project-archive").performClick()
                compose.waitUntil(10_000) {
                    c.storage.projects
                        .find(id)
                        ?.archivedAt != null
                }
                assertNotNull(c.storage.sessions.find(member))
                compose.onNodeWithTag("project-archive").performClick()
                compose.waitUntil(10_000) {
                    c.storage.projects
                        .find(id)
                        ?.archivedAt == null
                }
                compose.onNodeWithTag("project-edit").performClick()
                compose.onNodeWithTag("project-name").performScrollTo().performTextReplacement("$name-renamed")
                compose.onNodeWithTag("project-save").performClick()
                compose.waitUntil(10_000) {
                    c.storage.projects
                        .find(id)
                        ?.name == "$name-renamed"
                }
                assertEquals(
                    directory,
                    c.storage.sessions
                        .resolve(member)
                        .directoryRef,
                )
                compose.onNodeWithTag("project-delete").performClick()
                compose.onNodeWithTag("project-delete-confirm").assertIsDisplayed().performClick()
                compose.waitUntil(10_000) { c.storage.projects.find(id) == null }
                assertNotNull(c.storage.sessions.find(member))
                assertEquals(
                    directory,
                    c.storage.sessions
                        .resolve(member)
                        .directoryRef,
                )
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("screen-projects").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("screen-projects").assertIsDisplayed()
            } finally {
                project?.let {
                    c.storage.projects
                        .find(it)
                        ?.let { record -> service.delete(record) }
                }
                session?.let { c.storage.sessions.archive(it, System.currentTimeMillis()) }
            }
        }
}
