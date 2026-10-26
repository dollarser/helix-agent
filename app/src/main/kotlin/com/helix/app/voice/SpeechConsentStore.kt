package com.helix.app.voice

import android.content.Context
import com.helix.app.internal.LineStore
import com.helix.app.internal.PrefsLineStore
import java.security.MessageDigest

/** First-use disclosure, scoped to the exact destination and login identity; never grants model/tool authority. */
class SpeechConsentStore(
    private val store: LineStore,
) {
    constructor(context: Context) : this(PrefsLineStore(context, "speech-consent", synchronous = true))

    fun approved(
        kind: String,
        destination: String,
        identity: String,
    ): Boolean {
        val key = key(kind, destination, identity)
        return grants()?.contains(key) == true
    }

    fun approve(
        kind: String,
        destination: String,
        identity: String,
    ): Boolean {
        val key = key(kind, destination, identity)
        val previous = grants() ?: return false
        return try {
            store.setLines("approved", (previous.filterNot { it == key } + key).takeLast(64))
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun grants(): List<String>? =
        try {
            store.lines("approved")
        } catch (_: IllegalStateException) {
            null
        } catch (_: ClassCastException) {
            null
        }

    private fun key(
        kind: String,
        destination: String,
        identity: String,
    ): String {
        require(kind in setOf("asr", "tts") && destination.isNotBlank() && identity.isNotBlank())
        return MessageDigest
            .getInstance("SHA-256")
            .digest("$kind\n$destination\n$identity".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
