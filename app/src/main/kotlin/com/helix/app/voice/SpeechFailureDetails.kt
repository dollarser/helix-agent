package com.helix.app.voice

import com.helix.app.account.AccountRequestFailure
import com.helix.provider.api.wire.HttpFailureDetails
import com.helix.provider.api.wire.SocketHandshakeFailure

/** Propagate structured diagnostics only, never exception messages or audio/text payloads. */
internal fun speechFailureDetails(error: Throwable): HttpFailureDetails? =
    when (error) {
        is SpeechTransportFailure -> HttpFailureDetails(200, requestId = error.requestId)
        is SpeechHttpFailure -> error.details
        is AccountRequestFailure -> error.details
        is SocketHandshakeFailure -> error.details
        is ContinuousCaptureFailure -> error.cause?.let(::speechFailureDetails)
        else -> null
    }
