package com.helix.app.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore
import org.junit.After
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

    @After
    fun restorePinnedLanguage() {
        // Restore the run's deterministic zh-CN pin (HelixAndroidJUnitRunner forces ZH_CN for the
        // run) so any UI test class running after this one renders the canonical Chinese copy
        // regardless of the device system locale.
        AppLanguageStore.applyChoice(context, AppLanguage.ZH_CN)
    }

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
        // HXA-069: the recognizer language now follows the app UI-language choice, so pin each
        // choice explicitly for a deterministic assertion (independent of the run's global pin).
        // "Follow system" is the ONLY choice that leaves the recognizer on the device locale.
        AppLanguageStore.applyChoice(context, AppLanguage.SYSTEM)
        val intent = speech.buildIntent(context)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE))
        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
        )
        // HXA-067 + HXA-069: "follow system" => no explicit language, the device default applies.
        assertNull(
            "the recognizer must be started with the system locale (no EXTRA_LANGUAGE)",
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
        )
        assertNull(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE))
    }

    @Test
    fun aFixedUiLanguageSetsTheRecognizerLanguage() {
        // HXA-069: a fixed in-app language is passed as EXTRA_LANGUAGE so recognition defaults to
        // what the user reads Helix in (the pure choice->tag map is unit-tested in
        // SpeechRecognitionLauncherTest; here the real intent carries it).
        AppLanguageStore.applyChoice(context, AppLanguage.ZH_CN)
        assertEquals(
            "zh-CN",
            speech.buildIntent(context).getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
        )
        AppLanguageStore.applyChoice(context, AppLanguage.EN)
        assertEquals(
            "en",
            speech.buildIntent(context).getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
        )
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
