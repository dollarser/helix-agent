package com.helix.app.voice

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Global user intent; service/destination consent remains independently required. */
class ReplyAudioSettings(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences("reply-audio-v1", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(preferences.getBoolean("automatic", false))
    val automatic = state.asStateFlow()

    @SuppressLint("UseKtx") // Publish only after a checked durable commit; KTX edit returns Unit.
    suspend fun setAutomatic(value: Boolean) =
        withContext(Dispatchers.IO) {
            check(preferences.edit().putBoolean("automatic", value).commit())
            state.value = value
        }
}

/** Service-owned starts survive view recreation; each model step may speak once. */
internal class AutomaticReplyAudio {
    private val started = mutableSetOf<String>()

    fun start(
        frame: VisibleReplyFrame?,
        enabled: Boolean,
    ): Boolean {
        if (!enabled || frame == null) return false
        val visible = frame.phase == VisibleReplyFrame.Phase.STREAMING && frame.text.isNotBlank()
        return visible && started.add(frame.speechId)
    }
}
