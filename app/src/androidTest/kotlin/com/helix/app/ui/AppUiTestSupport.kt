package com.helix.app.ui

import android.content.Context
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
    val container = container()
    container.firstLaunch.reset()
    container.profileStore.switchTo(SafetyProfile.STANDARD)
    // Close any session a previous test left open: openSessionId is state of
    // the PROCESS-level ChatService (it outlives activity recreation by
    // design — an in-flight turn keeps running), so without this the recreated
    // activity lands on the stale conversation instead of the session list.
    container.chatService.closeSession()
    // recreate must run on the main thread (Activity contract).
    runOnUiThread { activity.recreate() }
    waitForIdle()
    dismissFirstLaunchIfNeeded()
    // Settle closeSession's async screen refresh (it re-renders on the
    // service's IO scope) so the test lands on the deterministic session list.
    waitUntil(10_000) {
        onAllNodesWithTag("chat-session-list").fetchSemanticsNodes().isNotEmpty()
    }
}

/** Dismisses the first-launch notice when it is on screen (idempotent). */
fun AndroidComposeTestRule<*, *>.dismissFirstLaunchIfNeeded() {
    if (onAllNodesWithTag("first-launch-continue").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag("first-launch-continue").performClick()
        waitForIdle()
    }
}

/**
 * Deletes every persisted provider (secrets + test statuses + bindings prune
 * with it). [ProviderService.delete] is a suspend Room/Keystore operation: the
 * test thread blocks (via runBlocking) until every row is actually gone, so
 * subsequent assertions see the final state.
 */
fun deleteAllProviders(container: AppContainer) {
    runBlocking {
        container.providerService.rows.value
            .forEach { row -> container.providerService.delete(row.id) }
    }
}

/** Opens the navigation drawer and navigates to the given route tag (e.g. "settings"). */
fun AndroidComposeTestRule<*, *>.navigateTo(route: String) {
    onNodeWithTag("open-navigation").performClick()
    waitForIdle()
    onNodeWithTag("navigation-$route").performClick()
    waitForIdle()
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
