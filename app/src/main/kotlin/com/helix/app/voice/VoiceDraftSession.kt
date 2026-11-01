package com.helix.app.voice

/** One capture's origin and known buffer acknowledgements. Manual edits revoke further speech writes. */
class VoiceDraftSession(
    private val origin: String,
) {
    private val acknowledgements = linkedSetOf(fingerprint(origin))
    private var active = true

    fun accepts(input: String): Boolean = active && fingerprint(input) in acknowledgements

    fun commit(
        transcript: String,
        input: String,
    ): String? {
        if (!accepts(input)) return null
        val draft = VoiceInputMapper.appendDraft(origin, transcript)
        check(acknowledgements.size <= 34) { "VOICE_DRAFT_LIMIT" }
        acknowledgements += fingerprint(draft)
        return draft
    }

    fun preview(transcript: String): String = VoiceInputMapper.appendDraft(origin, transcript)

    fun cancel() {
        active = false
    }

    private fun fingerprint(text: String) =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
