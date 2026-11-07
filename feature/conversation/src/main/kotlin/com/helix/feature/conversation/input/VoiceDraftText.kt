package com.helix.feature.conversation.input

fun appendVoiceDraft(
    input: String,
    transcript: String,
): String =
    when {
        transcript.isBlank() -> input
        input.isEmpty() -> transcript
        else -> "$input $transcript"
    }
