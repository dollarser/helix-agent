package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.R
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Production UI and Room, with only owned synthetic records; no provider calls. */
class ProjectSessionsUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun memberRenameArchiveAndRestoreRefreshWithoutMembershipChange(): Unit =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            val service = requireNotNull(c.projects)
            val name = "Rows-${UUID.randomUUID()}"
            val project = service.save(null, name, "", "", null)
            val member = service.newSession(project, null, null)
            try {
                compose.setContent {
                    MaterialTheme { ProjectSessions(service, project, setOf(member), false, {}, {}) }
                }
                awaitText(name)
                val renamed = "$name-renamed"
                c.storage.sessions.updateDetails(
                    member,
                    renamed,
                    c.storage.sessions
                        .resolve(member)
                        .directoryRef,
                )
                awaitText(renamed)
                compose.onNodeWithText(name).assertDoesNotExist()
                c.storage.sessions.archive(member, System.currentTimeMillis())
                awaitText(app.getString(R.string.project_archived))
                compose.onNodeWithText(renamed).assertIsDisplayed()
                c.storage.sessions.restore(member)
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(app.getString(R.string.project_archived)).fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithText(renamed).assertIsDisplayed()
            } finally {
                service.delete(requireNotNull(c.storage.projects.find(project)))
                if (c.storage.sessions
                        .resolve(member)
                        .archivedAt == null
                ) {
                    c.storage.sessions.archive(member, System.currentTimeMillis())
                }
            }
        }

    @Test
    @Suppress("LongMethod") // Keep live candidate changes, archive and route transitions inside one cleanup scope.
    fun candidatesUpdateAndArchivedOrChangedProjectClosesJoinMode(): Unit =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            val service = requireNotNull(c.projects)
            val first = service.save(null, "First-${UUID.randomUUID()}", "", "", null)
            val second = service.save(null, "Second-${UUID.randomUUID()}", "", "", null)
            val route = mutableStateOf(first)
            val archived = mutableStateOf(false)
            val candidate = UUID.randomUUID().toString()
            val title = "Candidate-$candidate"
            try {
                compose.setContent {
                    MaterialTheme { ProjectSessions(service, route.value, emptySet(), archived.value, {}, {}) }
                }
                compose.onNodeWithTag("project-add-session").performClick()
                c.storage.sessions.create(candidate, title, null, null, System.currentTimeMillis())
                awaitText(title)
                compose.onNodeWithTag("project-join-$candidate").assertIsDisplayed()
                c.storage.sessions.archive(candidate, System.currentTimeMillis())
                compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
                c.storage.sessions.restore(candidate)
                awaitText(title)
                service.archive(requireNotNull(c.storage.projects.find(first)), true)
                compose.runOnIdle { archived.value = true }
                compose.onNodeWithTag("project-add-session").assertDoesNotExist()
                compose.onNodeWithTag("project-join-$candidate").assertDoesNotExist()
                service.archive(requireNotNull(c.storage.projects.find(first)), false)
                compose.runOnIdle { archived.value = false }
                compose.onNodeWithTag("project-add-session").assertIsDisplayed()
                compose.onNodeWithTag("project-join-$candidate").assertDoesNotExist()
                compose.onNodeWithTag("project-add-session").performClick()
                awaitText(title)
                compose.runOnIdle { route.value = second }
                compose.onNodeWithTag("project-join-$candidate").assertDoesNotExist()
                compose.onNodeWithTag("project-add-session").assertIsDisplayed()
            } finally {
                listOf(first, second).forEach { id ->
                    c.storage.projects
                        .find(id)
                        ?.let { service.delete(it) }
                }
                c.storage.sessions.find(candidate)?.takeIf { it.archivedAt == null }?.let {
                    c.storage.sessions.archive(candidate, System.currentTimeMillis())
                }
            }
        }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
