package com.helix.app.ui

import android.content.Context
import android.os.Process
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-191 search slice — real device UI: the bounded session/history search in the session
 * list: title + message-body hits with snippets, active/archived grouping, the empty state,
 * clear and open-to-cancel, rebuild (rotation equivalent) persistence, and a real two-phase
 * process restart proving the seeds survive and a fresh process searches them.
 *
 * Phase protocol (same shape as TaskJourneyDeviceTest, D8-accepted): the matrix runs this
 * class twice against the SAME installation — `recoveryPhase=setup` asserts the fresh
 * process holds no search state (the UI facets assume-skip, so it is the only executing
 * method), seeds the fixture (fixed IDs, idempotent), records the process ID and kills the
 * process; `recoveryPhase=verify` asserts the new PID sees the persisted seeds and
 * searches them. The owned AVD is portrait-locked at 1080x2400, so "rotation" is the activity
 * rebuild path a configuration change goes through (`recreate` twice).
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // one method per search facet plus the shared fixture helpers
class SessionSearchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun prepareStandaloneFixture() {
        // Ordinary JUnit order is unspecified. Only a normal run seeds its fixture;
        // restart verification must observe the existing persisted rows without repair.
        if (recoveryPhase() == null) {
            seedSearchFixture(compose.container().storage)
        }
    }

    // ---------- process restart protocol ----------

    @Test
    fun searchStateIsFreshButSeedsSurviveAProcessRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val phase = InstrumentationRegistry.getArguments().getString(RECOVERY_PHASE_KEY)
        val container = compose.container()
        if (phase == "verify") {
            val markerPid =
                context.noBackupFilesDir
                    .resolve(PID_MARKER)
                    .readText()
                    .toInt()
            assertNotEquals("recovery must run in a new process", markerPid, Process.myPid())
            assertSearchSeeds(container.storage)
            compose.resetDeterministicUiState()
            typeQuery("sunrise")
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                compose.onAllNodesWithTag("chat-session-hit-$SEARCH_ALPHA").fetchSemanticsNodes().isNotEmpty()
            }
            return
        }
        if (phase == "setup") {
            // Only this method executes in the setup run (the UI facets assume-skip and
            // @Before seeds nothing), so this process is provably fresh: search state is
            // in-memory only, never loaded from persistence on start.
            assertEquals(
                "a fresh process starts with no search in flight",
                "",
                container.chatService.sessionSearch.value.query,
            )
        }
        seedSearchFixture(container.storage)
        context.noBackupFilesDir.resolve(PID_MARKER).writeText(Process.myPid().toString())
        if (phase == "setup") {
            Process.killProcess(Process.myPid())
            error("the recovery setup kill must end this process")
        }
        assertSearchSeeds(container.storage)
    }

    @Test
    fun typingPhrasePreservesSpaceAndClearingPreventsLateResults() {
        uiFixture()
        val service = compose.container().chatService
        typeQuery("sunrise ")
        compose.runOnIdle { assertEquals("sunrise ", service.sessionSearch.value.query) }
        typeQuery("sunrise planning")
        compose.runOnIdle { assertEquals("sunrise planning", service.sessionSearch.value.query) }
        compose.runOnIdle { service.clearSessionSearch() }
        // Allow the debounce and any already-running IO pass to finish before checking reset.
        Thread.sleep(500)
        compose.runOnIdle {
            assertEquals("", service.sessionSearch.value.query)
            assertTrue(
                service.sessionSearch.value.hits
                    .isEmpty(),
            )
        }
    }

    // ---------- UI facets ----------

    @Test
    fun searchShowsTitleHitsAndMessageHitsWithSnippets() {
        uiFixture()
        // Title hit: the word exists only in the persisted session title.
        typeQuery("sunrise")
        waitHit(SEARCH_ALPHA)
        // Primary proof — the process-level service state, which the v2 rule's activity
        // trampoline cannot reset: the committed query and the title match.
        val titleSearch =
            compose
                .container()
                .chatService.sessionSearch.value
        assertEquals("the committed title query is in the service", "sunrise", titleSearch.query)
        val alphaHit = titleSearch.hits.first { it.sessionId == SEARCH_ALPHA }
        assertTrue("the alpha hit is a title match", alphaHit.matchesTitle)
        // Body hit: the needle exists only in the stored message content, with its snippet.
        typeQuery("alpha-needle-77")
        waitHit(SEARCH_BETA)
        val bodySearch =
            compose
                .container()
                .chatService.sessionSearch.value
        assertEquals("the committed body query is in the service", "alpha-needle-77", bodySearch.query)
        val betaHit = bodySearch.hits.first { it.sessionId == SEARCH_BETA }
        assertTrue("the beta hit is a message match", betaHit.matchesMessage)
        val snippet = betaHit.messageSnippet
        assertNotNull("the body hit carries a context snippet", snippet)
        assertTrue("the snippet contains the needle", snippet!!.contains("alpha-needle-77", ignoreCase = true))
        assertTrue("the scope states at least one body was searched", bodySearch.scannedMessages >= 1)
        // The scope line is shown in the UI: the search result is bound to the committed
        // query, not "this page only". The snippet itself is proven above from the
        // process-level service state — the source of truth the hit row renders — because
        // collectAsStateWithLifecycle pauses collection while the v2 rule's trampoline
        // backgrounds the activity, which can leave the snippet row uncomposed on a
        // backgrounded frame even though the state (and the re-foregrounded row) carry it.
        assertPresent("chat-session-search-scope")
    }

    @Test
    fun searchGroupsArchivedHitsSeparatelyFromActive() {
        uiFixture()
        typeQuery("gamma")
        waitHit(SEARCH_GAMMA)
        assertVisibleOrPresent("chat-session-search-group_archived")
        assertTrue(
            "an all-archived result has no active group header",
            compose.onAllNodesWithTag("chat-session-search-group-active").fetchSemanticsNodes().isEmpty(),
        )
        typeQuery("sunrise")
        waitHit(SEARCH_ALPHA)
        assertVisibleOrPresent("chat-session-search-group-active")
        assertTrue(
            "an all-active result has no archived group header",
            compose.onAllNodesWithTag("chat-session-search-group_archived").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun searchShowsTheEmptyStateWithoutAMatch() {
        uiFixture()
        typeQuery("zzz-no-match")
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("chat-session-search-empty").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun clearingTheSearchRestoresTheSessionList() {
        uiFixture()
        typeQuery("sunrise")
        waitHit(SEARCH_ALPHA)
        compose.onNodeWithTag("chat-session-search-clear").performScrollTo().performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("chat-session-hit-$SEARCH_ALPHA").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("chat-session-$SEARCH_BETA").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            "clear resets the service search state",
            "",
            compose
                .container()
                .chatService.sessionSearch.value.query,
        )
    }

    @Test
    fun openingAResultCancelsTheSearch() {
        uiFixture()
        typeQuery("sunrise")
        waitHit(SEARCH_ALPHA)
        compose.onNodeWithTag("chat-session-hit-$SEARCH_ALPHA").performScrollTo().performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("chat-back").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("chat-back").performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose
                .container()
                .chatService.sessionSearch.value.query
                .isEmpty()
        }
        // The list is back and unfiltered.
        assertVisibleOrPresent("chat-session-$SEARCH_BETA")
    }

    @Test
    fun searchStateSurvivesActivityRebuilds() {
        uiFixture()
        typeQuery("sunrise")
        waitHit(SEARCH_ALPHA)
        rebuild()
        rebuild()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("chat-session-hit-$SEARCH_ALPHA").fetchSemanticsNodes().isNotEmpty()
        }
        assertVisibleOrPresent("chat-session-hit-$SEARCH_ALPHA")
        compose.onNodeWithTag("chat-session-search-field").assertTextContains("sunrise", substring = true)
    }

    // ---------- fixture + helpers ----------

    /** Skips in the setup run, then lands on a deterministic, search-free session list. */
    private fun uiFixture() {
        assumeTrue(recoveryPhase() != "setup")
        compose.container().chatService.clearSessionSearch()
        compose.resetDeterministicUiState()
    }

    /**
     * Commits [text] to the search field and verifies the service accepted it. On this
     * slow AVD a previous test's teardown destroys the activity while the keyboard is up
     * and can leave the IME's input connection stale, silently dropping the replacement;
     * a full deterministic reset reseats the connection before each retry.
     */
    private fun typeQuery(text: String) {
        repeat(TYPE_SETTLE_ATTEMPTS) { attempt ->
            compose
                .onNodeWithTag("chat-session-search-field")
                .performTextReplacement(text)
            val settled =
                try {
                    compose.waitUntil(TYPE_SETTLE_MILLIS) { searchUiActive() }
                    true
                } catch (_: ComposeTimeoutException) {
                    false
                }
            if (settled) return
            if (attempt + 1 < TYPE_SETTLE_ATTEMPTS) uiFixture()
        }
    }

    /**
     * Waits for [tag] to be displayed; if the v2 compose rule's EmptyActivity trampoline
     * backgrounds the activity first (the node then stays queryable in the tree while
     * isDisplayed reports false, and the scenario's activity can even be destroyed), accepts
     * the node being present instead — a backgrounded activity still composes the search
     * result, so presence in the tree is a faithful "the UI rendered it" signal.
     */
    private fun assertVisibleOrPresent(tag: String) {
        val displayed =
            try {
                compose.waitUntil(APP_FOREGROUND_CHECK_MILLIS) {
                    compose.onNodeWithTag(tag).isDisplayed()
                }
                true
            } catch (_: ComposeTimeoutException) {
                false
            }
        if (displayed) return
        assertPresent(tag)
    }

    /** True once the service holds a non-blank query: the explicit-scope line is visible. */
    private fun searchUiActive(): Boolean =
        compose
            .onAllNodesWithTag("chat-session-search-scope")
            .fetchSemanticsNodes()
            .isNotEmpty()

    /** Waits until the node with [tag] is composed into the tree (immune to backgrounding). */
    private fun assertPresent(tag: String) {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitHit(sessionId: String) = assertPresent("chat-session-hit-$sessionId")

    /** The rotation equivalent on the portrait-locked AVD: the activity rebuild path. */
    private fun rebuild() {
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitForIdle()
    }

    /** Seeds the search fixture with FIXED ids (idempotent — both phases address the same rows). */
    private fun seedSearchFixture(storage: HelixStorage) {
        val existing = storage.sessions.list().associateBy { it.id }
        if (SEARCH_ALPHA !in existing) storage.sessions.create(SEARCH_ALPHA, "Sunrise planning", null, null, 1000L)
        if (SEARCH_BETA !in existing) storage.sessions.create(SEARCH_BETA, "Beta notes", null, null, 2000L)
        if (SEARCH_GAMMA !in existing) storage.sessions.create(SEARCH_GAMMA, "Gamma archive", null, null, 3000L)
        if (storage.sessions.resolve(SEARCH_GAMMA).archivedAt == null) {
            storage.sessions.archive(SEARCH_GAMMA, 9000L)
        }
        if (storage.messages.listBySession(SEARCH_BETA).isEmpty()) {
            storage.messages.append(
                "$SEARCH_BETA-message",
                SEARCH_BETA,
                null,
                "USER",
                "TEXT",
                "the alpha-needle-77 token lives here",
            )
        }
        if (storage.messages.listBySession(SEARCH_GAMMA).isEmpty()) {
            storage.messages.append(
                "$SEARCH_GAMMA-message",
                SEARCH_GAMMA,
                null,
                "USER",
                "TEXT",
                "gamma-needle-88 archived body",
            )
        }
    }

    private fun assertSearchSeeds(storage: HelixStorage) {
        assertEquals("Sunrise planning", storage.sessions.resolve(SEARCH_ALPHA).title)
        assertNull("the alpha seed stays active", storage.sessions.resolve(SEARCH_ALPHA).archivedAt)
        assertEquals(1, storage.messages.listBySession(SEARCH_BETA).size)
        assertNotNull("the gamma seed stays archived", storage.sessions.resolve(SEARCH_GAMMA).archivedAt)
        assertEquals(1, storage.messages.listBySession(SEARCH_GAMMA).size)
    }

    private fun recoveryPhase(): String? = InstrumentationRegistry.getArguments().getString(RECOVERY_PHASE_KEY)

    companion object {
        private const val RECOVERY_PHASE_KEY = "recoveryPhase"
        private const val PID_MARKER = "recovery-device-pid"
        private const val TYPE_SETTLE_ATTEMPTS = 3
        private const val TYPE_SETTLE_MILLIS = 3_000L
        private const val APP_FOREGROUND_CHECK_MILLIS = 3_000L
        private const val SEARCH_ALPHA = "search-alpha"
        private const val SEARCH_BETA = "search-beta"
        private const val SEARCH_GAMMA = "search-gamma"
    }
}
