package com.helix.app.voice

import com.helix.app.account.AccountRequestFailure
import com.helix.provider.api.wire.SocketHandshakeFailure
import java.io.IOException

/** Safe categories only: server bodies, credentials and transcript text never become UI errors. */
enum class SpeechFailure {
    NETWORK,
    TIMEOUT,
    AUTHENTICATION,
    PERMISSION,
    INVALID_REQUEST,
    HTTP,
    PROTOCOL,
    BUSY,
    SERVICE,
}

internal class SpeechHttpFailure(
    val status: Int,
    val details: com.helix.provider.api.wire.HttpFailureDetails =
        com.helix.provider.api.wire.HttpFailureDetails(
            status,
        ),
) : IllegalStateException("SPEECH_HTTP_$status")

/** The response started, but transport failed; its request ID is diagnostic, never proof of success. */
internal class SpeechTransportFailure(
    val transportCause: IOException,
    val requestId: String,
) : IOException("Speech transport failed", transportCause)

internal class SpeechProtocolFailure(
    message: String,
) : IOException(message)

internal class SpeechServiceFailure : IOException("Speech service rejected input")

internal fun speechFailure(error: Throwable): SpeechFailure =
    when (error) {
        is SpeechTransportFailure -> {
            speechFailure(error.transportCause)
        }

        is SpeechHttpFailure -> {
            speechHttpFailure(error.status)
        }

        is SocketHandshakeFailure -> {
            speechHttpFailure(error.status)
        }

        is AccountRequestFailure -> {
            speechHttpFailure(error.status)
        }

        is SpeechProtocolFailure -> {
            SpeechFailure.PROTOCOL
        }

        is SpeechServiceFailure -> {
            SpeechFailure.SERVICE
        }

        is SpeechSocketFailure -> {
            speechSocketFailure(error.closeCode)
        }

        is ContinuousCaptureFailure -> {
            error.cause?.let(::speechFailure) ?: SpeechFailure.SERVICE
        }

        is java.net.SocketTimeoutException -> {
            SpeechFailure.TIMEOUT
        }

        is IOException -> {
            SpeechFailure.NETWORK
        }

        is IllegalArgumentException, is IllegalStateException -> {
            SpeechFailure.PROTOCOL
        }

        else -> {
            SpeechFailure.SERVICE
        }
    }

private fun speechHttpFailure(status: Int): SpeechFailure =
    when (status) {
        401 -> SpeechFailure.AUTHENTICATION
        403 -> SpeechFailure.PERMISSION
        400, 422 -> SpeechFailure.INVALID_REQUEST
        408, 504 -> SpeechFailure.TIMEOUT
        in 500..599 -> SpeechFailure.SERVICE
        429 -> SpeechFailure.BUSY
        else -> SpeechFailure.HTTP
    }

private fun speechSocketFailure(closeCode: Int): SpeechFailure =
    when (closeCode) {
        1013 -> SpeechFailure.BUSY
        1011 -> SpeechFailure.SERVICE
        1006 -> SpeechFailure.NETWORK
        else -> SpeechFailure.PROTOCOL
    }
