package com.helix.app.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device acceptance for HXA-067 voice input: it exercises the REAL Android seam
 * ([SpeechRecognitionLauncher]) — the system `SpeechRecognizer.isRecognitionAvailable` query, the
 * `ACTION_RECOGNIZE_SPEECH` launch intent, and the `EXTRA_RESULTS` extraction that feeds the pure
 * [VoiceInputMapper].
 *
 * The two verification emulators intentionally exercise BOTH real availability paths: the API 29
 * AOSP image ships no speech-recognition app (the genuine "unavailable" path), while the API 36
 * image does (the genuine "available" path). The availability test therefore asserts CONSISTENCY
 * with the real query rather than a fixed value — a device test must not assume which recognizer
 * apps an image installs — so across the emulator pair both real paths are covered; the pure
 * `preCheck(false)` / `preCheck(true)` mapping is unit-tested in [VoiceInputMapperTest]. The success
 * / cancel / no-result mappings are driven through synthetic `Intent`s carrying the exact extras a
 * recognizer returns. The "recognised text becomes an editable draft that is NEVER auto-sent"
 * guarantee is structural (a [VoiceInputMapper.Outcome.Draft] carries only text; the composer turns
 * it into `input` and never calls send) and inherits the HXA-056 share-draft E2E precedent.
 */
@RunWith(AndroidJUnit4::class)
class SpeechRecognitionDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val speech = SpeechRecognitionLauncher()

    @Test
    fun thePreCheckReflectsTheRealSystemAvailabilityQuery() {
        // Consistency, not a fixed value: on the recognizer-less API 29 image this asserts the real
        // Unavailable path; on the recognizer-equipped API 36 image it asserts the real Available
        // path. Either way preCheck must agree with the live system query.
        val available = speech.isAvailable(context)
        val expected =
            if (available) {
                VoiceInputMapper.Outcome.Available
            } else {
                VoiceInputMapper.Outcome.Unavailable
            }
        assertSame(expected, VoiceInputMapper.preCheck(available))
    }

    @Test
    fun theLaunchIntentTargetsTheSystemRecognizerInSystemLocale() {
        val intent = speech.buildIntent(context)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE))
        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
        )
        // HXA-067: start recognition in the SYSTEM locale — no explicit language is set.
        assertNull(
            "the recognizer must be started with the system locale (no EXTRA_LANGUAGE)",
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
        )
        assertNull(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE))
    }

    @Test
    fun aSuccessfulRecognitionResultMapsToTheEditableDraft() {
        val data =
            Intent().putExtra(
                RecognizerIntent.EXTRA_RESULTS,
                arrayListOf("你好 世界", "你好世界"),
            )
        assertEquals(
            VoiceInputMapper.Outcome.Draft("你好 世界"),
            speech.mapResult(VoiceInputMapper.RESULT_OK, data),
        )
    }

    @Test
    fun aSuccessfulRecognitionWithNoTranscriptIsACleanCancel() {
        assertSame(
            VoiceInputMapper.Outcome.Cancelled,
            speech.mapResult(VoiceInputMapper.RESULT_OK, Intent()),
        )
    }

    @Test
    fun aCanceledRecognitionWithNoTranscriptIsACleanCancel() {
        assertSame(
            VoiceInputMapper.Outcome.Cancelled,
            speech.mapResult(VoiceInputMapper.RESULT_CANCELED, Intent()),
        )
    }

    @Test
    fun aCanceledRecognitionIgnoresAnyStaleTranscript() {
        // A non-OK result must never yield a draft, even if the data intent carried leftover
        // results.
        val data =
            Intent().putExtra(
                RecognizerIntent.EXTRA_RESULTS,
                arrayListOf("stale"),
            )
        assertSame(
            VoiceInputMapper.Outcome.Cancelled,
            speech.mapResult(VoiceInputMapper.RESULT_CANCELED, data),
        )
    }

    @Test
    fun aNullDataIntentIsACleanCancel() {
        // A plain cancel returns no data intent at all; extraction must not throw.
        assertSame(
            VoiceInputMapper.Outcome.Cancelled,
            speech.mapResult(VoiceInputMapper.RESULT_CANCELED, null),
        )
    }
}
