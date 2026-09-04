package com.helix.app.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The Android seam for voice input (roadmap HXA-067): it queries the SYSTEM speech-recognition
 * capability, builds the `ACTION_RECOGNIZE_SPEECH` intent, and extracts the raw pieces of the
 * returned activity result for the pure-JVM [VoiceInputMapper]. It holds no mic, records nothing,
 * and keeps no listener — the user-initiated system UI does the recording and Helix only receives
 * the transcript on return, so there is no background, resident listening.
 *
 * The recognizer is started with the SYSTEM locale: no `EXTRA_LANGUAGE` / `EXTRA_LANGUAGE_PREFERENCE`
 * is set, so the system recognition UI uses its own default language and the user can still edit
 * the transcript there (HXA-069 will later resource the copy and wire the default to the App locale).
 *
 * The activity result carries only `resultCode` and, on success, the `EXTRA_RESULTS` transcript
 * list — the platform exposes no error-code extra, so there is nothing further to extract; the
 * pure mapper turns the two pieces into the [VoiceInputMapper.Outcome].
 */
class SpeechRecognitionLauncher {
    /** Whether the device offers any `ACTION_RECOGNIZE_SPEECH` handler at all. */
    fun isAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * The system recognizer launch intent: free-form model, NO explicit language (system locale),
     * and the calling package tagged per the [RecognizerIntent] contract.
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
            )

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
}
