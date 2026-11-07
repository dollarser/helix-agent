package com.helix.app.voice

import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokens
import com.helix.app.account.MemoryAccountTokens
import com.helix.app.account.accountResponse
import com.helix.app.internal.InMemoryLineStore
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit owners keep capture routing fresh without consulting an Android application. */
class SpeechCaptureRoutingTest {
    private val offline = StreamingAsrFactory { error("No native model is opened by this routing test") }

    private fun account(tokens: AccountTokens? = null) =
        AccountSession(
            MemoryAccountTokens(tokens),
            object : WireClient {
                override suspend fun open(request: WireRequest) = accountResponse(200, "{}")
            },
            now = { 100L },
        )

    @Test fun eachCaptureReadsCurrentSettingsAndKeepsItsOriginalDestination() {
        val settings = SpeechSettingsStore(InMemoryLineStore())
        val owner = account()
        assertSame(offline, settings.asrFactory(owner) { offline })
        settings.save(SpeechServiceSettings(true, "http://127.0.0.1:18767/asr", "http://127.0.0.1:18768/tts"))
        val customCapture = settings.asrFactory(owner) { error("Custom service must not select offline") }
        assertTrue(customCapture.uploadsAudio)
        assertFalse(customCapture.supportsPartial)
        assertEquals("http://127.0.0.1:18767/asr", customCapture.destination)
        settings.save(SpeechServiceSettings())
        assertSame(offline, settings.asrFactory(owner) { offline })
        assertEquals("http://127.0.0.1:18767/asr", customCapture.destination)
    }

    @Test fun restoredAccountSelectsStreamingAndSignOutInvalidatesTheCapturedOwner() =
        runBlocking {
            val settings = SpeechSettingsStore(InMemoryLineStore())
            val owner = account(AccountTokens("synthetic-access", "synthetic-refresh", 10000, 20000, "owner"))
            assertSame(offline, settings.asrFactory(owner) { offline })
            owner.restore()
            val capture = settings.asrFactory(owner) { error("Signed-in automatic routing must use streaming") }
            assertTrue(capture.uploadsAudio)
            assertTrue(capture.supportsPartial)
            assertEquals("wss://api.sunlingzhang.com/ws/asr", capture.destination)
            owner.logout()
            assertThrows(IllegalStateException::class.java) { capture.open() }
            assertSame(offline, settings.asrFactory(owner) { offline })
        }

    @Test fun explicitAccountRouteFailsClosedWithoutStartingOfflineOnSignOut() {
        val settings = SpeechSettingsStore(InMemoryLineStore())
        settings.save(SpeechServiceSettings(httpEnabled = true, useAccountLogin = true, webSocket = true))
        val capture = settings.asrFactory(account()) { error("Explicit account service must not fall back") }
        assertTrue(capture.uploadsAudio)
        assertThrows(IllegalStateException::class.java) { capture.open() }
    }
}
