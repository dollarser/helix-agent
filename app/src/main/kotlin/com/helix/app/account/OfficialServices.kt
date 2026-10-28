package com.helix.app.account

/** Owner-supplied gateway contract. Only these bases are deployment configuration. */
object OfficialServices {
    const val ACCOUNT_BASE_URL = "https://accounts.sunlingzhang.com"
    const val INFERENCE_BASE_URL = "https://api.sunlingzhang.com/v1"
    const val SPEECH_WS_BASE_URL = "wss://api.sunlingzhang.com"

    fun asr(webSocket: Boolean): String =
        if (webSocket) "$SPEECH_WS_BASE_URL/ws/asr" else "$INFERENCE_BASE_URL/audio/transcriptions"

    fun tts(webSocket: Boolean): String =
        if (webSocket) "$SPEECH_WS_BASE_URL/ws/tts" else "$INFERENCE_BASE_URL/audio/speech"
}
