package com.helix.app.voice

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.account.AccountStatus
import com.helix.app.internal.PrefsLineStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-only configuration check: no account access, network, microphone or preference writes. */
class OfficialSpeechConfigurationDeviceTest {
    @Test fun installedOfficialEndpointsAndStoredSelectionRemainIndependentOfCustomAddresses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = PrefsLineStore(instrumentation.targetContext, "speech-services", synchronous = true)
        val before = store.lines("settings")
        val preferences = SpeechSettingsStore(store)
        val official =
            SpeechServiceSettings(
                httpEnabled = true,
                customAsrEndpoint = "https://custom.test/asr",
                customTtsEndpoint = "https://custom.test/tts",
                useAccountLogin = true,
                webSocket = true,
                asrModel = "self-hosted",
                ttsModel = "self-hosted",
            )
        assertEquals("wss://api.hilex.me/ws/asr", official.asrEndpoint)
        assertEquals("wss://api.hilex.me/ws/tts", official.ttsEndpoint)
        val custom = official.copy(useAccountLogin = false, webSocket = false)
        assertEquals("https://custom.test/asr", custom.asrEndpoint)
        assertEquals("https://custom.test/tts", custom.ttsEndpoint)
        val configured = preferences.forAccount(AccountStatus.SIGNED_IN)
        if (preferences.automatic) {
            assertTrue(configured.httpEnabled && configured.useAccountLogin && !configured.webSocket)
            assertEquals("https://api.hilex.me/v1/audio/transcriptions", configured.asrEndpoint)
            assertEquals("https://api.hilex.me/v1/audio/speech", configured.ttsEndpoint)
        } else {
            assertEquals(preferences.read(), configured)
        }
        assertEquals(before, store.lines("settings"))
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                val selection = if (preferences.automatic) "automatic" else "explicit-preserved"
                putString(
                    "stream",
                    "officialSpeech selection=$selection asr=${official.asrEndpoint} tts=${official.ttsEndpoint}",
                )
            },
        )
    }
}
