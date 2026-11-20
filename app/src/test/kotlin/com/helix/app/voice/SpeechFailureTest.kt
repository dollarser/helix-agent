package com.helix.app.voice

import com.helix.app.account.AccountRequestFailure
import com.helix.app.ui.speechFailureText
import com.helix.provider.api.wire.SocketHandshakeFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class SpeechFailureTest {
    @Test fun httpAndHandshakeFailuresRetainSafeCategoriesWithoutServerContent() {
        for (status in listOf(302, 400, 401, 403, 404, 408, 422, 429, 500, 502, 503, 504, 599)) {
            val expected =
                when (status) {
                    401 -> SpeechFailure.AUTHENTICATION
                    403 -> SpeechFailure.PERMISSION
                    400, 422 -> SpeechFailure.INVALID_REQUEST
                    408, 504 -> SpeechFailure.TIMEOUT
                    in 500..599 -> SpeechFailure.SERVICE
                    429 -> SpeechFailure.BUSY
                    else -> SpeechFailure.HTTP
                }
            assertEquals(expected, speechFailure(SpeechHttpFailure(status)))
            assertEquals(expected, speechFailure(SocketHandshakeFailure(status)))
            assertEquals(expected, speechFailure(AccountRequestFailure(status)))
        }
    }

    @Test fun transportTimeoutProtocolAndServiceRejectionAreDistinct() {
        assertEquals(SpeechFailure.NETWORK, speechFailure(IOException("private URL and credentials")))
        assertEquals(SpeechFailure.TIMEOUT, speechFailure(SocketTimeoutException("private URL")))
        assertEquals(SpeechFailure.PROTOCOL, speechFailure(SpeechProtocolFailure("untrusted response")))
        assertEquals(SpeechFailure.PROTOCOL, speechFailure(IllegalArgumentException("malformed response")))
        assertEquals(SpeechFailure.SERVICE, speechFailure(SpeechServiceFailure()))
        assertEquals(SpeechFailure.BUSY, speechFailure(SpeechSocketFailure(1013)))
        assertEquals(SpeechFailure.SERVICE, speechFailure(SpeechSocketFailure(1011)))
        assertEquals(SpeechFailure.NETWORK, speechFailure(SpeechSocketFailure(1006)))
        assertEquals(SpeechFailure.PROTOCOL, speechFailure(SpeechSocketFailure(1000)))
    }

    @Test fun continuousCapturePreservesAuthenticationFailureInsteadOfReportingNetworkError() {
        val failure = ContinuousCaptureFailure(VoiceCapture.Failure.CONNECTION, SocketHandshakeFailure(401))
        assertEquals(SpeechFailure.AUTHENTICATION, speechFailure(failure))
    }

    @Test fun uiUsesOnlyFixedResourceIdsRegardlessOfUntrustedErrorText() {
        assertEquals(
            speechFailureText(speechFailure(IOException("safe"))),
            speechFailureText(speechFailure(IOException("https://user:secret@example.test/?token=secret"))),
        )
        assertEquals(
            com.helix.app.R.string.speech_failure_service,
            speechFailureText(speechFailure(SocketHandshakeFailure(503))),
        )
    }

    @Test fun permissionAndInvalidParametersHaveDistinctActionableUiAndPreserveDetails() {
        val detail =
            com.helix.provider.api.wire
                .HttpFailureDetails(422, "unsupported format", "fixture-request")
        val error = SpeechHttpFailure(422, detail)
        assertEquals(detail, speechFailureDetails(error))
        assertEquals(
            com.helix.app.R.string.speech_failure_invalid_request,
            speechFailureText(speechFailure(error)),
        )
        assertEquals(
            com.helix.app.R.string.speech_failure_permission,
            speechFailureText(speechFailure(SocketHandshakeFailure(403))),
        )
    }
}
