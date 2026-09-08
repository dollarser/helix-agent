package com.helix.app.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device regression for the chat screen refresh's STALE-SESSION contract (found while
 * de-flaking the approval device tests, which open sessions seeded by id):
 *
 * [com.helix.app.AppContainer] exposes `openSession(id)` as a public service entry and the
 * refresh that follows it runs ASYNCHRONOUSLY on the chat service's work scope. The open id
 * is in-memory (never persisted), so a refresh can legitimately observe an id whose row does
 * not exist — opened by id before the row was persisted, or orphaned by a storage loss
 * (sessions are archived, never deleted, but a future retention wipe would orphan it too).
 *
 * The contract under test: such a refresh DEGRADES to the session list
 * (`openSessionId -> null`) and never throws. Before the fix, `refreshScreen` resolved the
 * id straight into `SessionRepository.resolve`, which throws for an unknown id — an uncaught
 * exception on that IO coroutine KILLS THE WHOLE APP PROCESS (observed: `FATAL EXCEPTION:
 * DefaultDispatcher-worker` with `IllegalArgumentException: session not found: flow-session`
 * while the device suite opened a not-yet-seeded session).
 */
@RunWith(AndroidJUnit4::class)
class ChatSessionLifecycleDeviceTest {
    private lateinit var container: com.helix.app.AppContainer

    /** Per-run suffix: the device Room persists across test runs — ids must be unique. */
    private val run = System.nanoTime()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        container = (app as HelixApplication).appContainer
    }

    @Test
    fun openingARealSessionShowsIt() {
        val sessionId = "lifecycle-real-$run"
        container.storage.sessions.create(sessionId, "lifecycle real", null, null, System.currentTimeMillis())
        container.chatService.openSession(sessionId)
        // The refresh runs on the service's work scope: poll (bounded) for it to land.
        awaitOpenSession(sessionId)
        assertEquals(sessionId, container.chatService.screen.value.openSessionId)
    }

    @Test
    fun openingAnUnknownSessionDegradesToTheSessionListWithoutCrashing() {
        // Never persisted. Before the fix this killed the app process during the async
        // refresh; the test dying with it would be the regression.
        val ghost = "lifecycle-ghost-$run"
        container.chatService.openSession(ghost)
        // Self-heal: the refresh must settle on the session list (no open session).
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (container.chatService.screen.value.openSessionId == null) return
            Thread.sleep(50)
        }
        val actual = container.chatService.screen.value.openSessionId
        assertNull("a refresh of an unknown session id must degrade to the session list, was: $actual", actual)
    }

    @Test
    fun providerFreeSessionAndShareDraftNeverRetainThePreviousProviderBadge() {
        val providerId = createBadgeProvider()
        val bound = "lifecycle-bound-$run"
        val unbound = "lifecycle-unbound-$run"
        try {
            container.storage.sessions.create(bound, "bound", providerId, "fixture", System.currentTimeMillis())
            container.storage.sessions.create(unbound, "unbound", null, null, System.currentTimeMillis())
            container.chatService.openSession(bound)
            awaitOpenSession(bound)
            assertEquals(
                "badge-$run",
                container.chatService.screen.value.badge
                    ?.displayName,
            )
            container.chatService.openSession(unbound)
            awaitOpenSession(unbound)
            assertNull("unbound session must not inherit a previous badge", container.chatService.screen.value.badge)
            container.chatService.openSession(bound)
            awaitOpenSession(bound)
            container.chatService.acceptShareDraft("share-$run", emptyList())
            val deadline = System.currentTimeMillis() + 10_000
            while (container.chatService.screen.value.shareDraftText != "share-$run" &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
            val draft = container.chatService.screen.value
            assertEquals("share-$run", draft.shareDraftText)
            assertNull("share draft must offer explicit provider binding", draft.badge)
            assertNull(
                container.storage.sessions
                    .resolve(requireNotNull(draft.openSessionId))
                    .providerId,
            )
        } finally {
            container.chatService.closeSession()
            runBlocking { container.providerService.delete(providerId) }
        }
    }

    private fun createBadgeProvider(): String =
        runBlocking {
            container.providerService.create(
                ProviderDraft(
                    templateId = null,
                    displayName = "badge-$run",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    endpoint = NormalizedEndpoint.parse("https://badge.invalid/v1"),
                    model = "fixture",
                    headersJson = "{}",
                    credentialRequired = false,
                    cleartext = null,
                    templateNotes = emptyList(),
                ),
                apiKey = null,
                cleartextConfirmed = false,
            )
        }

    /** Bounded poll until [expected] is the open session (the refresh is asynchronous). */
    private fun awaitOpenSession(expected: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (container.chatService.screen.value.openSessionId == expected) return
            Thread.sleep(50)
        }
        val actual = container.chatService.screen.value.openSessionId
        assertTrue("the refresh must land on the opened session, was: $actual", actual == expected)
    }
}
