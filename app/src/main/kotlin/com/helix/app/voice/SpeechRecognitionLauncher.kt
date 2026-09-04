package com.helix.app.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore

/**
 * The Android seam for voice input (roadmap HXA-067): it queries the SYSTEM speech-recognition
 * capability, builds the `ACTION_RECOGNIZE_SPEECH` intent, and extracts the raw pieces of the
 * returned activity result for the pure-JVM [VoiceInputMapper]. It holds no mic, records nothing,
 * and keeps no listener — the user-initiated system UI does the recording and Helix only receives
 * the transcript on return, so there is no background, resident listening.
 *
 * The recognizer's default language follows the app's UI-language choice (HXA-069): a fixed
 * in-app language (Simplified Chinese / English) is passed as `EXTRA_LANGUAGE` so recognition
 * defaults to what the user reads Helix in, while "follow system" sets no `EXTRA_LANGUAGE` and the
 * system recognition UI keeps its own (device-locale) default. The user can still edit the
 * transcript in the system UI either way.
 *
 * The activity result carries only `resultCode` and, on success, the `EXTRA_RESULTS` transcript
 * list — the platform exposes no error-code extra, so there is nothing further to extract; the
 * pure mapper turns the two pieces into the [VoiceInputMapper.Outcome].
 */
class SpeechRecognitionLauncher {
    /** Whether the device offers any `ACTION_RECOGNIZE_SPEECH` handler at all. */
    fun isAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * The system recognizer launch intent: free-form model, the recognition language defaulted to
     * the app's chosen UI language (a fixed zh-CN/en — see [recognitionLanguage]; "follow system"
     * leaves it unset so the device default applies), and the calling package tagged per the
     * [RecognizerIntent] contract.
     */
    fun buildIntent(context: Context): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(
                RecognizerIntent.EXTRA_CALLING_PACKAGE,
                context.packageName,
            ).putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            ).apply {
                // HXA-069: `stored` is the read-only last choice (no API-33 adoption write here);
                // a fixed language is passed as EXTRA_LANGUAGE, null (follow system) sets nothing.
                recognitionLanguage(AppLanguageStore.stored(context))
                    ?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            }

    /**
     * Extracts the platform pieces and delegates the decision to [VoiceInputMapper]: the result
     * code and the `EXTRA_RESULTS` transcript list (index 0 = best match). A `null` data intent
     * (a plain cancel) simply yields an empty list.
     */
    fun mapResult(
        resultCode: Int,
        data: Intent?,
    ): VoiceInputMapper.Outcome {
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        return VoiceInputMapper.mapResult(resultCode, results)
    }

    companion object {
        /**
         * The BCP-47 tag for the recognizer derived from the app UI-language choice: a fixed
         * in-app language maps to its tag, "follow system" maps to null (let the device default
         * apply). Pure (no [Context]) and in a companion object so it is unit-testable without an
         * instance.
         */
        fun recognitionLanguage(choice: AppLanguage): String? =
            when (choice) {
                AppLanguage.ZH_CN -> "zh-CN"
                AppLanguage.EN -> "en"
                AppLanguage.SYSTEM -> null
            }
    }
}
