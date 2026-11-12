package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.R
import com.helix.app.plugin.PluginService
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillLoader
import com.helix.extensions.skills.SkillRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class SkillUseReviewDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()

    @Test fun unsupportedAndUnappliedFieldsAreExplainedWithoutRunningAnything() {
        val repo = repository("context: fork\nmodel: external\nargument-hint: file\nallowed-tools: Bash\n")
        val key = repo.list().single().key
        var calls = 0
        compose.setContent {
            MaterialTheme {
                SkillUseDialog(
                    repo,
                    "s",
                    key,
                    SkillUseActions(SkillUseContext(), {
                        calls++
                        true
                    }, {}),
                )
            }
        }
        awaitReview()
        compose.onNodeWithTag("skill-unsupported").assertIsDisplayed()
        compose.onNodeWithTag("skill-unapplied").assertIsDisplayed()
        compose.onNodeWithTag("skill-invocation-confirm").assertIsNotEnabled()
        assertEquals(0, calls)
        assertTrue(repo.list().single().enabled)
    }

    @Test fun onceAndSessionEnablementAreSeparateAndFailedHandoffDoesNotClose() {
        val repo = repository("")
        val key = repo.list().single().key
        repo.setEnabled(key, false, SkillEnablementScope.GLOBAL)
        var attempts = 0
        compose.setContent {
            MaterialTheme {
                SkillUseDialog(
                    repo,
                    "s",
                    key,
                    SkillUseActions(SkillUseContext(), {
                        attempts++
                        false
                    }, {}),
                )
            }
        }
        awaitReview()
        compose.onNodeWithTag("skill-invocation-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("skill-session-enable").performClick()
        compose.waitUntil(10_000) { repo.list("s").single().enabled }
        compose.onNodeWithTag("skill-invocation-arguments").assertDoesNotExist()
        compose.onNodeWithTag("skill-invocation-confirm").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { attempts == 1 }
        compose.onNodeWithTag("skill-use-failed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("skill-invocation-confirm").assertIsEnabled()
        assertEquals(false, repo.list().single().enabled)
        assertEquals(true, repo.list("s").single().enabled)
        assertEquals(false, repo.list("other").single().enabled)
    }

    @Test fun missingDependencyRequiresExplicitScopedPluginSelection() {
        val c = app.appContainer
        val session = "skill-dependency-${UUID.randomUUID()}"
        c.storage.sessions.create(session, "Dependency validation", null, null, 0)
        try {
            val defaults = c.pluginService.list().associate { it.id to c.pluginService.catalog.defaultSelected(it.id) }
            val service = dependencyService(c, session)
            val before = c.pluginService.catalog.selected(session)
            val key =
                c.skillRepository
                    .list(session)
                    .single { it.key.name == "web-research" }
                    .key
            var invoked = 0
            val visible = mutableStateOf(true)
            compose.setContent {
                MaterialTheme {
                    if (visible.value) {
                        SkillUseDialog(
                            c.skillRepository,
                            session,
                            key,
                            SkillUseActions(SkillUseContext(plugins = service), {
                                invoked++
                                true
                            }, { visible.value = false }),
                        )
                    }
                }
            }
            awaitReview()
            compose.onNodeWithTag("skill-configure-dependency").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("skill-invocation-confirm").assertIsNotEnabled()
            assertEquals(before, c.pluginService.catalog.selected(session))
            compose.onNodeWithTag("skill-configure-dependency").performClick()
            selectBrowserExplicitly(c, session, before)
            compose.onNodeWithTag("skill-invocation-confirm").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { invoked == 1 }
            assertTrue("bundled-plugin:browser" in c.pluginService.catalog.selected(session))
            assertEquals(
                defaults,
                c.pluginService.list().associate {
                    it.id to
                        c.pluginService.catalog.defaultSelected(it.id)
                },
            )
        } finally {
            c.storage.deleteSessionPermanently(session)
        }
    }

    @Test fun replacingIdentityCancelsTheOldPendingInvocation() {
        val repo = repository("")
        val original = repo.list().single().key
        val key = mutableStateOf(original)
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var started = 0
        var completed = 0
        compose.setContent {
            MaterialTheme {
                SkillUseDialog(
                    repo,
                    "s",
                    key.value,
                    SkillUseActions(
                        SkillUseContext(),
                        {
                            started++
                            gate.await()
                        },
                        {},
                        completed = { completed++ },
                    ),
                )
            }
        }
        awaitReview()
        compose.onNodeWithTag("skill-invocation-confirm").performClick()
        compose.waitUntil(10_000) { started == 1 }
        compose.runOnIdle { key.value = original.copy(snapshotHash = "different-snapshot") }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("skill-use-failed").fetchSemanticsNodes().isNotEmpty()
        }
        gate.complete(true)
        compose.waitForIdle()
        assertEquals(0, completed)
        assertEquals(original, repo.list().single().key)
    }

    @Test fun previewOnlySkillHasNeitherInvocationNorEnablementControl() {
        val repo = repository("user-invocable: false\ndisable-model-invocation: true\n")
        val key = repo.list().single().key
        var invoked = 0
        compose.setContent {
            MaterialTheme {
                SkillUseDialog(
                    repo,
                    "s",
                    key,
                    SkillUseActions(SkillUseContext(), {
                        invoked++
                        true
                    }, {}),
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("skill-preview-only").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("skill-invocation-confirm").assertDoesNotExist()
        compose.onNodeWithTag("skill-session-enable").assertDoesNotExist()
        assertEquals(0, invoked)
    }

    private fun dependencyService(
        c: com.helix.app.AppContainer,
        session: String,
    ): PluginService {
        c.pluginService.catalog.selectFromUser(session, "bundled-plugin:browser", false)
        assertTrue("bundled-plugin:browser" !in c.pluginService.catalog.effectiveSelected(session))
        val result =
            PluginService(
                app,
                c.storage,
                c.mcpService,
                c.skillImportService,
                c.skillRepository,
                catalog = c.pluginService.catalog,
                nativeRegistry = c.pluginRegistry,
                browserHostAvailable = { true },
                toolNames = { emptySet() },
            )
        val key =
            c.skillRepository
                .list(session)
                .single { it.key.name == "web-research" }
                .key
        assertEquals(
            false,
            result.skillSupport
                .inspect(key, session)
                .web
                ?.provided,
        )
        return result
    }

    private fun selectBrowserExplicitly(
        c: com.helix.app.AppContainer,
        session: String,
        before: Set<String>,
    ) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("connector-session-bundled-plugin:browser").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("connector-session-bundled-plugin:browser").performClick()
        assertEquals(before, c.pluginService.catalog.selected(session))
        compose.waitUntil(10_000) {
            compose.onNodeWithTag("connector-session-bundled-plugin:browser").fetchSemanticsNode().config[
                SemanticsProperties.ToggleableState,
            ] == androidx.compose.ui.state.ToggleableState.On &&
                !compose
                    .onNodeWithTag(
                        "plugin-session-apply",
                    ).fetchSemanticsNode()
                    .config
                    .contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithTag("plugin-session-apply").assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("connector-session-panel").fetchSemanticsNodes().isEmpty()
        }
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithTag("skill-invocation-confirm")
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.contains(SemanticsProperties.Disabled) == false
        }
    }

    private fun awaitReview() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("skill-session-enable").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("skill-unsupported").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun repository(fields: String): SkillRepository {
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "skill-review")
        val doc = SkillLoader().loadBuiltIn("---\nname: review\ndescription: Review\n$fields---\nGuide", "review")
        return SkillRepository(root.resolve("snapshots"), root.resolve("state"), root.resolve("trash"), listOf(doc))
    }
}
