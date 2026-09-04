package com.helix.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure-JVM half of voice input (roadmap HXA-067): the system `ACTION_RECOGNIZE_SPEECH`
 * activity result maps to exactly one of unavailable / available / draft / cancelled. A success is
 * INERT editable text — a [VoiceInputMapper.Outcome.Draft] carries only text and no "send" signal,
 * so the recognised text can only become composer input, never an auto-send. The activity API
 * returns no error code, so user-cancel and recognition-failure both collapse to the benign
 * [VoiceInputMapper.Outcome.Cancelled] (no draft, no error, no send); the unavailable state is the
 * pre-launch gate. The Android extraction (real `Intent` / `RecognizerIntent`) is device-verified by
 * [SpeechRecognitionDeviceTest].
 */
class VoiceInputMapperTest {
    @Test
    fun preCheckAvailableYieldsAvailableToLaunch() {
        assertSame(VoiceInputMapper.Outcome.Available, VoiceInputMapper.preCheck(true))
    }

    @Test
    fun preCheckUnavailableYieldsUnavailable() {
        assertSame(VoiceInputMapper.Outcome.Unavailable, VoiceInputMapper.preCheck(false))
    }

    @Test
    fun aSuccessfulResultYieldsTheFirstNonBlankMatchAsADraft() {
        val outcome =
            VoiceInputMapper.mapResult(
                VoiceInputMapper.RESULT_OK,
                listOf("", "  你好 世界  ", "你好世界"),
            )
        assertEquals(VoiceInputMapper.Outcome.Draft("你好 世界"), outcome)
    }

    @Test
    fun aSuccessfulResultWithNoTranscriptIsACleanCancel() {
        assertSame(
            VoiceInputMapper.Outcome.Cancelled,
            VoiceInputMapper.mapResult(VoiceInputMapper.RESULT_OK, emptyList()),
        )
    }

    @Test
    fun aSuccessfulResultWithOnlyWhitespaceIsACleanCancel() {
        assertSame(
            VoiceInputMapper.Outcome.Cancelled,
            VoiceInputMapper.mapResult(VoiceInputMapper.RESULT_OK, listOf("   ", "\t")),
        )
    }

    @Test
    fun aUserCancelIsACleanCancel() {
        assertSame(
            VoiceInputMapper.Outcome.Cancelled,
            VoiceInputMapper.mapResult(VoiceInputMapper.RESULT_CANCELED, emptyList()),
        )
    }

    @Test
    fun aNonOkResultIgnoresAnyStaleTranscript() {
        // Even if a cancel/failure intent somehow carried leftover results, a non-OK code must not
        // produce a draft.
        assertSame(
            VoiceInputMapper.Outcome.Cancelled,
            VoiceInputMapper.mapResult(VoiceInputMapper.RESULT_CANCELED, listOf("stale")),
        )
    }

    @Test
    fun anUnknownResultCodeFailsClosedToCancel() {
        // A result code that is neither OK nor a recognised cancel still yields no draft.
        assertSame(
            VoiceInputMapper.Outcome.Cancelled,
            VoiceInputMapper.mapResult(1, listOf("x")),
        )
    }

    @Test
    fun aDraftIsInertTextWithNoSendSignal() {
        // The draft carries ONLY text — there is no field, flag, or companion that would let the
        // voice path send. A consumer can only place this text into editable input.
        val outcome = VoiceInputMapper.mapResult(VoiceInputMapper.RESULT_OK, listOf("帮我订票"))
        assertTrue(outcome is VoiceInputMapper.Outcome.Draft)
        assertEquals("帮我订票", (outcome as VoiceInputMapper.Outcome.Draft).text)
    }
}
