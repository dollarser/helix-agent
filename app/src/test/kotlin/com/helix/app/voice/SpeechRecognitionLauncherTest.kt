package com.helix.app.voice

import com.helix.app.language.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the HXA-069 wiring of the recognizer's default language to the app's UI-language choice:
 * a fixed in-app language maps to its BCP-47 tag (passed as `EXTRA_LANGUAGE`), while "follow
 * system" maps to null so the system recognition UI keeps its own device-locale default. The pure
 * mapping is unit-tested here; the real `Intent` construction (the actual `EXTRA_LANGUAGE` extra)
 * is device-verified by [SpeechRecognitionDeviceTest].
 */
class SpeechRecognitionLauncherTest {
    @Test
    fun simplifiedChineseMapsToTheZHCnTag() {
        assertEquals("zh-CN", SpeechRecognitionLauncher.recognitionLanguage(AppLanguage.ZH_CN))
    }

    @Test
    fun englishMapsToTheEnTag() {
        assertEquals("en", SpeechRecognitionLauncher.recognitionLanguage(AppLanguage.EN))
    }

    @Test
    fun followSystemLeavesTheRecognizerDefaultUntouched() {
        // null => buildIntent sets no EXTRA_LANGUAGE, so the device default applies.
        assertNull(SpeechRecognitionLauncher.recognitionLanguage(AppLanguage.SYSTEM))
    }
}
