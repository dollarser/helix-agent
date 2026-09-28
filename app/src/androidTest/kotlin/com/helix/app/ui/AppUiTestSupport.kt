package com.helix.app.ui

import android.content.Context
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore
import com.helix.core.model.SafetyProfile
import kotlinx.coroutines.runBlocking

/*
 * Shared HXA-028 instrumented-test helpers.
 *
 * App data survives across instrumented runs on the emulator (Room database +
 * SharedPreferences), so every UI test first re-arms deterministic state
 * through the service seams — FirstLaunchStore.reset and
 * SafetyProfileStore.switchTo(STANDARD) — then recreates the activity so the
 * fresh gate state is recomposed.
 */

/** IO-backed UI projections wait up to ten seconds, returning as soon as state arrives; not a latency SLA. */
internal const val ASYNC_UI_TIMEOUT_MILLIS = 10_000L

/** The production container of the running app (instrumentation runs in-app). */
fun AndroidComposeTestRule<*, *>.container(): AppContainer = (activity.application as HelixApplication).appContainer

/**
 * Re-arms the first-launch gate + the STANDARD profile, recreates the
 * activity, and waits for the new composition. Because the gate is re-armed,
 * the fresh composition shows the notice over the shell — the helper
 * dismisses it so tests land on a deterministic, gate-free shell (the
 * FirstLaunchNoticeTest exercises the gate itself and does not use this).
 */
fun AndroidComposeTestRule<*, *>.resetDeterministicUiState() {
    waitForIdle()
    val container = container()
    container.firstLaunch.reset()
    container.profileStore.switchTo(SafetyProfile.STANDARD)
    // Conversation-first fixture: every UI test starts from a fresh ephemeral conversation.
    // History/search tests must explicitly navigate to their secondary routes.
    // Keep the reset in one UI turn so Conversation's null-session effect cannot interleave.
    runOnUiThread {
        container.chatService.closeSession()
        container.chatService.newSessionDraft()
    }
    waitUntil(10_000) { container.chatService.screen.value.isDraft }
    runOnUiThread { activity.recreate() }
    waitForIdle()
    dismissFirstLaunchIfNeeded()
    waitUntil(10_000) {
        container.chatService.screen.value.openSessionId != null &&
            onAllNodesWithTag("chat-header").fetchSemanticsNodes().isNotEmpty()
    }
}

/** Dismisses the first-launch notice when it is on screen (idempotent). */
fun AndroidComposeTestRule<*, *>.dismissFirstLaunchIfNeeded() {
    if (onAllNodesWithTag("first-launch-continue").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag("first-launch-continue").performScrollTo().performClick()
        waitForIdle()
    }
}

/**
 * Deletes editable provider configurations while retaining Runtime-managed rows.
 * [ProviderService.delete] also prunes their secrets, statuses and bindings; the
 * test thread waits for each suspend Room/Keystore operation to finish.
 */
fun deleteEditableProviders(container: AppContainer) {
    runBlocking {
        container.providerService.rows.value
            .filterNot { it.managedExternally }
            .forEach { row -> container.providerService.delete(row.id) }
    }
}

/** Selects a control in the editable fixture row, excluding retained subscription rows. */
fun editableProviderTag(tag: String) = hasTestTag(tag) and editableProviderRow()

fun editableProviderText(text: String) = hasText(text, substring = true) and editableProviderRow()

private fun editableProviderRow() =
    hasAnyAncestor(
        hasTestTag("provider-row") and hasAnyDescendant(hasTestTag("provider-edit")),
    )

/** Navigates through the production IA. Secondary routes must be reached through their landing. */
fun AndroidComposeTestRule<*, *>.navigateTo(route: String) {
    if (route == CONVERSATION_HISTORY_ROUTE || route == CONVERSATION_SEARCH_ROUTE) {
        navigatePrimary("sessions")
        onNodeWithTag("open-navigation").performClick()
        waitForIdle()
        onNodeWithTag(
            if (route == CONVERSATION_HISTORY_ROUTE) "drawer-all-conversations" else "drawer-search-conversations",
        ).performScrollTo().performClick()
        waitForIdle()
        return
    }
    val path =
        when (route) {
            SETUP_READINESS_ROUTE -> {
                "setup" to listOf("setup-open-readiness")
            }

            SETUP_CAPABILITIES_ROUTE -> {
                "setup" to listOf("setup-open-capabilities")
            }

            SETUP_RUNTIME_ROUTE -> {
                "setup" to listOf("setup-open-runtime")
            }

            SETTINGS_DEFAULTS_ROUTE -> {
                "settings" to listOf("settings-open-defaults")
            }

            SETTINGS_PERMISSIONS_ROUTE -> {
                "settings" to listOf("settings-open-permissions")
            }

            SETTINGS_SYSTEM_PERMISSIONS_ROUTE -> {
                "settings" to listOf("settings-open-permissions", "settings-system-permissions")
            }

            SETTINGS_AUDIT_ROUTE -> {
                "settings" to listOf("settings-open-audit")
            }

            else -> {
                route to emptyList()
            }
        }
    navigatePrimary(path.first)
    path.second.forEach { tag ->
        onNodeWithTag(tag).performScrollTo().performClick()
        waitForIdle()
    }
}

private fun AndroidComposeTestRule<*, *>.navigatePrimary(route: String) {
    repeat(4) {
        if (onAllNodesWithTag("open-navigation").fetchSemanticsNodes().isNotEmpty()) return@repeat
        if (onAllNodesWithTag("navigate-back").fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithTag("navigate-back").performClick()
            waitForIdle()
        }
    }
    onNodeWithTag("open-navigation").performClick()
    waitForIdle()
    if (route == "sessions") {
        val currentTag = "drawer-current-conversation"
        if (onAllNodesWithTag(currentTag).fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithTag(currentTag).performScrollTo().performClick()
        } else {
            onNodeWithTag("drawer-new-conversation").performScrollTo().performClick()
        }
        waitUntil(10_000) { onAllNodesWithTag("chat-header").fetchSemanticsNodes().isNotEmpty() }
        return
    }
    val destinationTag = "navigation-$route"
    val groupTag =
        when (route) {
            "tasks", "artifacts", "git", "files", "browser", "terminal" -> "navigation-group-work"
            "models", "extensions", "setup" -> "navigation-group-configure"
            else -> null
        }
    if (groupTag != null && onAllNodesWithTag(destinationTag).fetchSemanticsNodes().isEmpty()) {
        onNodeWithTag(groupTag).performScrollTo().performClick()
        waitForIdle()
    }
    onNodeWithTag(destinationTag).performScrollTo().performClick()
    waitUntil(10_000) {
        onAllNodesWithTag(destinationTag).fetchSemanticsNodes().isEmpty() ||
            !onNodeWithTag(destinationTag).isDisplayed()
    }
}

/**
 * HXA-069: a zh-CN [Context] for the locale-deterministic UI fixtures. The headless
 * `createComposeRule()` composes in the app context's locale — en-US on an English-locale
 * emulator, because [HelixApplication] is instantiated before the runner's ZH_CN pin takes
 * effect. These fixtures assert the app's canonical Chinese-first copy, so they compose inside
 * a zh-CN `LocalContext` (the same createConfigurationContext primitive the app applies via
 * AppLanguageStore.wrapForLocale) to stay independent of the device locale.
 */
fun canonicalZhContext(): Context =
    AppLanguageStore.wrapForLocale(
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
        AppLanguageStore.localeListFor(AppLanguage.ZH_CN),
    )
