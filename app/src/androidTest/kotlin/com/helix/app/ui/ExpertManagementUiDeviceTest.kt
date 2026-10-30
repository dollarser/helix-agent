package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.core.storage.repository.ExpertLibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Uses the real drawer/editor/selector with owned synthetic data. No provider or connector requests. */
class ExpertManagementUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod") // A single user journey checks navigation, cancel, publish, selection and owned cleanup.
    fun drawerExpertCreationAndSessionSelection() =
        runBlocking {
            val app = compose.activity.application as HelixApplication
            val c = app.awaitAppContainer()
            val chat = c.chatService
            val token = UUID.randomUUID().toString().take(8)
            val name = "Synthetic expert $token"
            val target = "expert-ui-$token"
            val ids = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                c.storage.sessions.create(target, "Synthetic expert UI", null, null, 1)
                c.pluginService.catalog
                    .selected(target)
                    .forEach { c.pluginService.catalog.select(target, it, false) }
            }
            try {
                openDrawer()
                click("navigation-extensions")
                click("capabilities-tab-1")
                click("expert-create")
                fill(name)
                click("expert-editor-cancel")
                assertFalse(chat.savedExperts().await().any { it.profile.displayName == name })
                click("expert-create")
                fill(name)
                compose
                    .onNodeWithTag(
                        "expert-description",
                    ).performScrollTo()
                    .performTextInput("Synthetic capability preset")
                val reference = "native:" + com.helix.extensions.browser.BrowserPlugin.PLUGIN_ID
                compose.onNodeWithTag("expert-dependency-$reference").performScrollTo().performClick()
                compose.onNodeWithTag("session-expert-save").assertIsDisplayed()
                androidx.test.espresso.Espresso
                    .closeSoftKeyboard()
                capture("editor")
                click("session-expert-save")
                compose.waitUntil(15_000) {
                    c.storage.experts
                        .list()
                        .any { it.profile.displayName == name }
                }
                val definition = chat.savedExperts().await().single { it.profile.displayName == name }
                ids += definition.profile.id
                assertEquals(listOf(reference), definition.profile.recommendedConnectorIds)
                val exported = ExpertLibraryRepository.export(definition)
                assertEquals(definition, ExpertLibraryRepository.parseImport(exported, definition.profile.id))
                compose.waitUntil { compose.onAllNodesWithTag("expert-editor").fetchSemanticsNodes().isEmpty() }
                compose.waitUntil {
                    !compose
                        .onNodeWithTag("expert-create")
                        .fetchSemanticsNode()
                        .config
                        .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                }
                capture("library")
                click("expert-edit-${definition.profile.id}")
                compose
                    .onNodeWithTag("session-expert-instruction")
                    .performScrollTo()
                    .performTextReplacement("Discard this edit")
                click("expert-editor-cancel")
                assertEquals(definition, chat.savedExperts().await().single { it.profile.id == definition.profile.id })

                chat.openSession(target)
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == target }
                openDrawer()
                click("drawer-current-conversation")
                click("chat-add")
                click("composer-add-expert")
                click("expert-row-${definition.profile.id}")
                compose.waitUntil(10_000) {
                    c.storage.sessionExperts
                        .forSession(target)
                        ?.id == definition.profile.id
                }
                withContext(Dispatchers.IO) {
                    assertTrue(
                        c.pluginService.catalog
                            .selected(target)
                            .isEmpty(),
                    )
                    assertTrue(
                        c.pluginService.catalog
                            .effectiveSelected(target)
                            .isNotEmpty(),
                    )
                }
                assertTrue(chat.saveExpert(definition.profile.id, name, "Changed global only", "Edited card").await())
                assertEquals("Synthetic behavior only.", chat.loadSessionExpert(target).await()?.instruction)
                click("chat-add")
                click("composer-add-expert")
                click("session-expert-clear")
                compose.waitUntil(10_000) { c.storage.sessionExperts.forSession(target) == null }
                withContext(Dispatchers.IO) {
                    assertTrue(
                        c.pluginService.catalog
                            .effectiveSelected(target)
                            .isEmpty(),
                    )
                }
            } finally {
                chat.closeSession()
                // Only IDs recorded from this unique test fixture are removed.
                (
                    ids +
                        chat
                            .savedExperts()
                            .await()
                            .filter { it.profile.displayName == name }
                            .map { it.profile.id }
                ).distinct()
                    .forEach { chat.removeExpert(it).await() }
                withContext(Dispatchers.IO) { c.storage.deleteSessionPermanently(target) }
            }
        }

    private fun fill(name: String) {
        compose.onNodeWithTag("session-expert-name").performTextInput(name)
        compose
            .onNodeWithTag(
                "session-expert-instruction",
            ).performScrollTo()
            .performTextInput("Synthetic behavior only.")
    }

    private fun capture(stage: String) {
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
        if (androidx.test.platform.app.InstrumentationRegistry
                .getArguments()
                .getString("expertCapture") !=
            "true"
        ) {
            return
        }
        compose.waitForIdle()
        var bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            var stable = 0
            repeat(15) {
                if (stable < 2) {
                    android.os.SystemClock.sleep(100)
                    val next = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    stable = if (bitmap.sameAs(next)) stable + 1 else 0
                    bitmap.recycle()
                    bitmap = next
                }
            }
            check(stable >= 2) { "Expert screenshot did not settle" }
            val directory =
                instrumentation.targetContext.cacheDir
                    .resolve("expert-layout")
                    .apply { mkdirs() }
            directory.resolve("$stage.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun click(tag: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithTag(tag).performClick()
    }

    private fun openDrawer() {
        repeat(5) {
            if (compose.onAllNodesWithTag("open-navigation").fetchSemanticsNodes().isNotEmpty()) {
                click("open-navigation")
                return
            }
            if (compose.onAllNodesWithTag("navigate-back").fetchSemanticsNodes().isNotEmpty()) click("navigate-back")
            compose.waitForIdle()
        }
        error("Application navigation is unavailable")
    }
}
