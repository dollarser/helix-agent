package com.helix.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recognition results remain inert text; cancellation and explicit platform errors are distinct. */
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
    fun anUnknownResultCodeReportsFailureWithoutUsingStaleText() {
        // A result code that is neither OK nor a recognised cancel still yields no draft.
        assertEquals(
            VoiceInputMapper.Outcome.Failed(VoiceInputMapper.Failure.SERVICE),
            VoiceInputMapper.mapResult(1234, listOf("x")),
        )
    }

    @Test fun platformErrorsAreNotSilentlyTreatedAsCancellation() {
        val expected =
            listOf(
                VoiceInputMapper.Failure.NO_MATCH,
                VoiceInputMapper.Failure.SERVICE,
                VoiceInputMapper.Failure.SERVICE,
                VoiceInputMapper.Failure.NETWORK,
                VoiceInputMapper.Failure.AUDIO,
            )
        expected.forEachIndexed { index, failure ->
            assertEquals(
                VoiceInputMapper.Outcome.Failed(failure),
                VoiceInputMapper.mapResult(index + 1, listOf("stale")),
            )
        }
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
