package com.helix.app.voice

/** Maps Android results to editable text, cancellation or explicit failure. Never sends messages. */
object VoiceInputMapper {
    // `Activity` result code, mirrored as a plain int so this object stays pure JVM.
    fun appendDraft(
        input: String,
        transcript: String,
    ): String = if (input.isEmpty()) transcript else "$input $transcript"

    const val RESULT_OK: Int = -1 // Activity.RESULT_OK
    const val RESULT_CANCELED: Int = 0 // Activity.RESULT_CANCELED

    sealed class Outcome {
        /** Recognition succeeded: inert editable text for the composer (never auto-sent). */
        data class Draft(
            val text: String,
        ) : Outcome()

        /**
         * User cancellation, including vendors that collapse errors to RESULT_CANCELED.
         */
        object Cancelled : Outcome()

        /** No speech-recognition capability on the device (pre-launch check). */
        object Unavailable : Outcome()

        /** A recognizer exists (pre-launch check) — the caller launches the system UI. */
        object Available : Outcome()

        data class Failed(
            val reason: Failure,
        ) : Outcome()
    }

    enum class Failure { NO_MATCH, AUDIO, NETWORK, SERVICE }

    /** The pre-launch gate: is there any system recognizer to launch at all? */
    fun preCheck(available: Boolean): Outcome = if (available) Outcome.Available else Outcome.Unavailable

    /**
     * Maps a finished recognition. Only a [RESULT_OK] result with a non-blank transcript yields a
     * [Outcome.Draft]. Explicit platform errors remain distinguishable from user cancellation.
     */
    fun mapResult(
        resultCode: Int,
        results: List<String>,
    ): Outcome {
        if (resultCode != RESULT_OK) {
            return if (resultCode == RESULT_CANCELED) {
                Outcome.Cancelled
            } else {
                Outcome.Failed(
                    when (resultCode) {
                        1 -> Failure.NO_MATCH

                        // RecognizerIntent.RESULT_NO_MATCH
                        4 -> Failure.NETWORK

                        // RecognizerIntent.RESULT_NETWORK_ERROR
                        5 -> Failure.AUDIO

                        // RecognizerIntent.RESULT_AUDIO_ERROR
                        else -> Failure.SERVICE // Client, server or vendor-specific failure.
                    },
                )
            }
        }
        val text = results.firstOrNull { it.isNotBlank() }?.trim()
        return if (text != null) Outcome.Draft(text) else Outcome.Cancelled
    }
}
