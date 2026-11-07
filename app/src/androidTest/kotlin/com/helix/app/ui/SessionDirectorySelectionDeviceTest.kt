package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.app.files.FileManagerService
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import com.helix.ui.shared.R as SharedR

class SessionDirectorySelectionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentDirectoryAndBrowsingSurviveRecreationUntilExplicitConfirmation() =
        fixture { files ->
            val selected = mutableListOf<String?>()
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                MaterialTheme {
                    SessionDirectoryDialog(files, {}, initialReference = "scope:fixture:current") {
                        selected += it
                        true
                    }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("child").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(emptyList<String?>(), selected)
            compose.onNodeWithText("child").performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag("session-directory-confirm").assertIsEnabled() }.isSuccess
            }
            compose.onNodeWithTag("session-directory-confirm").performClick()
            compose.waitUntil(10_000) { selected.size == 1 }
            assertEquals(listOf("scope:fixture:current/child"), selected)
        }

    @Test fun projectAndIndependentChoicesRemainDistinctAndCancelDoesNotApply() =
        fixture { files ->
            val selected = mutableListOf<String?>()
            var dismissed = false
            compose.setContent {
                MaterialTheme {
                    SessionDirectoryDialog(files, { dismissed = true }, projectDirectory = "scope:fixture:project") {
                        selected += it
                        true
                    }
                }
            }
            compose.onNodeWithTag("session-directory-cancel").performClick()
            assertEquals(true, dismissed)
            assertEquals(emptyList<String?>(), selected)
            compose.onNodeWithTag("session-directory-project").performClick()
            compose.waitUntil(10_000) { selected.size == 1 }
            compose.onNodeWithTag("session-directory-private").performClick()
            compose.waitUntil(10_000) { selected.size == 2 }
            assertEquals(listOf("scope:fixture:project", null), selected)
        }

    @Test fun standaloneSelectionDoesNotOfferAProjectShortcut() =
        fixture { files ->
            compose.setContent {
                MaterialTheme { SessionDirectoryDialog(files, {}) { error("Cancel must not select") } }
            }
            compose.onNodeWithTag("session-directory-project").assertDoesNotExist()
            compose.onNodeWithTag("session-directory-cancel").performClick()
        }

    @Test fun invalidSavedReferenceCannotBeConfirmedButCanBeReplaced() =
        fixture { files ->
            var chosen = false
            compose.setContent {
                MaterialTheme {
                    SessionDirectoryDialog(files, {}, initialReference = "invalid-reference") {
                        assertEquals(null, it)
                        chosen = true
                        true
                    }
                }
            }
            compose.onNodeWithTag("session-directory-confirm").assertIsNotEnabled()
            compose.onNodeWithTag("session-directory-private").performClick()
            compose.waitUntil(10_000) { chosen }
        }

    @Test fun unresolvedDirectoryNeverUsesItsInternalScopeIdAsTheLabel() =
        fixture { files ->
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            compose.setContent {
                MaterialTheme {
                    SessionWorkspaceSection(files, "scope:unlisted:", true, sessionId = "owner") {}
                }
            }
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithText(context.getString(com.helix.app.R.string.workspace_unavailable))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("unlisted").assertDoesNotExist()
            compose.onNodeWithTag("session-settings-directory").assertIsDisplayed()
        }

    @Test fun projectDirectoryLabelWinsWhenTheProjectReusesASessionOwnedFolder() =
        fixture { files ->
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            compose.setContent {
                MaterialTheme {
                    SessionWorkspaceSection(
                        files,
                        "scope:owned:",
                        true,
                        sessionId = "owner",
                        projectDirectory = "scope:owned:",
                        projectName = "Shared project",
                    ) {}
                }
            }
            compose.onNodeWithTag("session-directory-details").assertDoesNotExist()
            compose.onNodeWithTag("session-settings-directory").performClick()
            compose.onNodeWithTag("session-directory-details").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("directory-access-capability").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(SharedR.string.chat_details_close)).performClick()
            compose
                .onNodeWithText(
                    context.getString(
                        com.helix.app.R.string.workspace_project_directory_label,
                        "Shared project",
                    ),
                ).assertIsDisplayed()
        }

    private fun fixture(block: (FileManagerService) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "directory-selection-")
        Files.createDirectories(root.resolve("current/child"))
        Files.createDirectories(root.resolve("project"))
        val roots = ScopeRootResolver { root }
        try {
            block(
                FileManagerService(
                    WorkspaceArtifactStore(roots),
                    roots,
                    "app",
                    manual =
                        com.helix.app.files.ManualFileOperations(
                            { scope ->
                                com.helix.app.files
                                    .NioManualFileBackend(scope, roots, false) {}
                            },
                            { true },
                        ),
                    workspaceSources = {
                        listOf(
                            com.helix.app.files.FileSource(
                                "fixture",
                                "External folder",
                                com.helix.app.files.FileSourceKind.WORKSPACE,
                                true,
                                workspaceBackend = "PATH",
                            ),
                            com.helix.app.files.FileSource(
                                "owned",
                                "Owned folder",
                                com.helix.app.files.FileSourceKind.WORKSPACE,
                                true,
                                workspaceBackend = "PATH",
                                ownerSessionId = "owner",
                            ),
                        )
                    },
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
