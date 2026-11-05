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

/** Observe an opted-in active Turn, then speak its final once. Historical navigation never starts audio. */
internal class AutomaticReplyAudio {
    private val started = mutableSetOf<Pair<String, String>>()
    private var pending: Pair<String, String>? = null

    fun leave() {
        pending = null
    }

    fun start(
        frame: VisibleReplyFrame?,
        enabled: Boolean,
    ): Boolean {
        if (!enabled || frame == null) {
            pending = null
            return false
        }
        val turn = frame.sessionId to frame.turnId
        return when (frame.phase) {
            VisibleReplyFrame.Phase.STREAMING, VisibleReplyFrame.Phase.WAITING -> {
                pending = turn
                false
            }

            VisibleReplyFrame.Phase.FINALIZING -> {
                if (pending != turn) pending = null
                false
            }

            VisibleReplyFrame.Phase.CANCELLED -> {
                pending = null
                false
            }

            VisibleReplyFrame.Phase.COMPLETED -> {
                val eligible = pending == turn && frame.text.isNotBlank()
                if (frame.text.isNotBlank() || pending != turn) pending = null
                eligible && started.add(turn)
            }
        }
    }
}
