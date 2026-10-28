package com.helix.app.voice

import com.helix.app.account.AccountStatus
import com.helix.app.internal.InMemoryLineStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRoutingTest {
    @Test fun signedInDefaultUsesOfficialStreamingWithoutChangingStoredPreference() {
        val lines = InMemoryLineStore()
        val settings = SpeechSettingsStore(lines)
        val route = settings.forAccount(AccountStatus.SIGNED_IN)
        assertTrue(route.httpEnabled && route.webSocket && route.useAccountLogin)
        assertEquals("wss://api.sunlingzhang.com/ws/asr", route.asrEndpoint)
        route.validate()
        assertTrue(lines.lines("settings").isEmpty())
        assertFalse(settings.forAccount(AccountStatus.SIGNED_OUT).httpEnabled)
    }

    @Test fun explicitOfflineAndCustomEndpointsAreNotOverriddenByLogin() {
        val store = SpeechSettingsStore(InMemoryLineStore())
        store.save(SpeechServiceSettings())
        assertFalse(store.forAccount(AccountStatus.SIGNED_IN).httpEnabled)
        val custom = SpeechServiceSettings(true, "http://127.0.0.1:18767/asr", "http://127.0.0.1:18768/tts")
        store.save(custom)
        assertEquals(custom, store.forAccount(AccountStatus.SIGNED_IN))
        assertEquals(custom, store.forAccount(AccountStatus.SIGNED_OUT))
    }

    @Test fun firstUseConsentSurvivesReopenAndDoesNotFollowAnotherTargetOrAccount() {
        val lines = InMemoryLineStore()
        val consent = SpeechConsentStore(lines)
        assertFalse(consent.approved("asr", "wss://asr.example.test/ws", "synthetic-account"))
        consent.approve("asr", "wss://asr.example.test/ws", "synthetic-account")
        assertTrue(SpeechConsentStore(lines).approved("asr", "wss://asr.example.test/ws", "synthetic-account"))
        assertFalse(consent.approved("asr", "wss://other.example.test/ws", "synthetic-account"))
        assertFalse(consent.approved("asr", "wss://asr.example.test/ws", "another-account"))
        assertFalse(consent.approved("tts", "wss://asr.example.test/ws", "synthetic-account"))
        assertFalse(lines.lines("approved").joinToString().contains("synthetic-account"))
    }

    @Test fun malformedSavedSettingsRemainAnErrorInsteadOfSilentFallback() {
        val lines = InMemoryLineStore()
        lines.setLines("settings", listOf("invalid"))
        val store = SpeechSettingsStore(lines)
        assertFalse(store.automatic)
        val route = store.forAccount(AccountStatus.SIGNED_IN)
        assertTrue(route.httpEnabled)
        assertTrue(runCatching { route.validate() }.isFailure)
    }

    @Test fun explicitAccountServiceDoesNotBecomeOfflineOnSignOut() {
        val store = SpeechSettingsStore(InMemoryLineStore())
        store.save(store.forAccount(AccountStatus.SIGNED_IN))
        val route = store.forAccount(AccountStatus.SIGNED_OUT)
        assertTrue(route.httpEnabled && route.useAccountLogin)
        assertEquals("wss://api.sunlingzhang.com/ws/asr", route.asrEndpoint)
    }

    @Test fun repeatedConsentDoesNotGrowHistoryAndOnlyRecentGrantsAreRetained() {
        val lines = InMemoryLineStore()
        val consent = SpeechConsentStore(lines)
        repeat(70) { consent.approve("tts", "https://speech.example.test/$it", "synthetic-account") }
        repeat(3) { consent.approve("tts", "https://speech.example.test/69", "synthetic-account") }
        assertEquals(64, lines.lines("approved").size)
        assertFalse(consent.approved("tts", "https://speech.example.test/0", "synthetic-account"))
        assertTrue(consent.approved("tts", "https://speech.example.test/69", "synthetic-account"))
    }

    @Test fun failedConsentSaveCannotAuthorizeAnUpload() {
        val consent =
            SpeechConsentStore(
                object : com.helix.app.internal.LineStore {
                    override fun lines(key: String): List<String> = emptyList()

                    override fun setLines(
                        key: String,
                        lines: List<String>,
                    ) {
                        error("synthetic persistence failure")
                    }
                },
            )
        assertFalse(consent.approve("asr", "https://speech.example.test/asr", "synthetic-account"))
        assertFalse(consent.approved("asr", "https://speech.example.test/asr", "synthetic-account"))
    }

    @Test fun unreadableConsentCannotAuthorizeOrOverwriteStoredChoices() {
        var writes = 0
        val consent =
            SpeechConsentStore(
                object : com.helix.app.internal.LineStore {
                    override fun lines(key: String): List<String> = error("synthetic unreadable preferences")

                    override fun setLines(
                        key: String,
                        lines: List<String>,
                    ) {
                        writes++
                    }
                },
            )
        assertFalse(consent.approved("tts", "https://speech.example.test/tts", "synthetic-account"))
        assertFalse(consent.approve("tts", "https://speech.example.test/tts", "synthetic-account"))
        assertEquals(0, writes)
    }

    @Test fun expiredOrUnavailableAccountDoesNotPromptAnAutomaticLocalDownload() {
        val local = SpeechServiceSettings()
        assertEquals(
            com.helix.app.R.string.speech_account_required,
            com.helix.app.ui.speechRoutingError(
                local,
                com.helix.app.account.AccountStatus.REAUTH_REQUIRED,
                false,
                false,
                false,
            ),
        )
        assertEquals(
            com.helix.app.R.string.speech_account_unavailable,
            com.helix.app.ui.speechRoutingError(
                local,
                com.helix.app.account.AccountStatus.UNAVAILABLE,
                false,
                false,
                false,
            ),
        )
    }

    @Test fun signedOutDefaultAndExplicitOfflineRemainUsableWithoutAccountStorage() {
        val local = SpeechServiceSettings()
        assertEquals(
            null,
            com.helix.app.ui.speechRoutingError(
                local,
                com.helix.app.account.AccountStatus.SIGNED_OUT,
                false,
                false,
                false,
            ),
        )
        assertEquals(
            null,
            com.helix.app.ui
                .speechRoutingError(local, null, true, false, false),
        )
    }

    @Test fun automaticExpiredOrUnavailableAccountKeepsTheOnlineRouteWithoutLocalFallback() {
        val store = SpeechSettingsStore(InMemoryLineStore())
        for (status in listOf(AccountStatus.REAUTH_REQUIRED, AccountStatus.UNAVAILABLE)) {
            val route = store.forAccount(status)
            assertTrue(route.httpEnabled && route.useAccountLogin && route.webSocket)
        }
    }

    @Test fun disablingOnlineAfterUsingItDoesNotRequireAccountAccessForOfflineSpeech() {
        val store = SpeechSettingsStore(InMemoryLineStore())
        val disabled = store.forAccount(AccountStatus.SIGNED_IN).copy(httpEnabled = false)
        store.save(disabled)
        assertFalse(store.forAccount(AccountStatus.UNAVAILABLE).httpEnabled)
        assertFalse(disabled.requiresAccount)
        assertEquals(
            null,
            com.helix.app.ui
                .speechRoutingError(disabled, null, true, false, false),
        )
    }
}
