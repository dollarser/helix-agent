package com.helix.app.voice

/**
 * The pure-JVM decision for voice input (roadmap HXA-067): it maps the SYSTEM speech-recognition
 * result — or the pre-launch availability check — into a sealed [Outcome] the UI renders. It has
 * no Android types and no side effects: a successful recognition comes back as inert
 * [Outcome.Draft] text that the composer turns into EDITABLE input; nothing here ever sends.
 *
 * The `ACTION_RECOGNIZE_SPEECH` activity result returns only `RESULT_OK` / `RESULT_CANCELED` with
 * NO error-code extra (verified against android-36: `RecognizerIntent` exposes no error-code key
 * and `SpeechRecognizer` has no error-code extraction method). A user cancel and a recognition
 * failure are therefore indistinguishable to Helix and both collapse to the benign [Outcome.Cancelled]
 * (no draft, no auto-send); the system recognition UI surfaces the cancel/error to the user itself.
 * The unavailable state is the pre-launch gate ([preCheck]).
 */
object VoiceInputMapper {
    // `Activity` result code, mirrored as a plain int so this object stays pure JVM.
    const val RESULT_OK: Int = -1 // Activity.RESULT_OK
    const val RESULT_CANCELED: Int = 0 // Activity.RESULT_CANCELED

    sealed class Outcome {
        /** Recognition succeeded: inert editable text for the composer (never auto-sent). */
        data class Draft(
            val text: String,
        ) : Outcome()

        /**
         * The user cancelled, the recognizer found no match, or recognition failed — all arrive as
         * a non-`RESULT_OK` result with no usable transcript: no draft, no error, no send.
         */
        object Cancelled : Outcome()

        /** No speech-recognition capability on the device (pre-launch check). */
        object Unavailable : Outcome()

        /** A recognizer exists (pre-launch check) — the caller launches the system UI. */
        object Available : Outcome()
    }

    /** The pre-launch gate: is there any system recognizer to launch at all? */
    fun preCheck(available: Boolean): Outcome = if (available) Outcome.Available else Outcome.Unavailable

    /**
     * Maps a finished recognition. Only a [RESULT_OK] result with a non-blank transcript yields a
     * [Outcome.Draft]; every other result (cancel, no match, failure) yields [Outcome.Cancelled].
     */
    fun mapResult(
        resultCode: Int,
        results: List<String>,
    ): Outcome {
        if (resultCode != RESULT_OK) return Outcome.Cancelled
        val text = results.firstOrNull { it.isNotBlank() }?.trim()
        return if (text != null) Outcome.Draft(text) else Outcome.Cancelled
    }
}
