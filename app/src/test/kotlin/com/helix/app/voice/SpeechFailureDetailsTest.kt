package com.helix.app.voice

import com.helix.app.account.AccountRequestFailure
import com.helix.provider.api.wire.HttpFailureDetails
import com.helix.provider.api.wire.SocketHandshakeFailure
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class SpeechFailureDetailsTest {
    @Test fun structuredDiagnosticsSurviveBothVoicePathsAndContinuousWrapping() {
        val details = HttpFailureDetails(429, "Daily quota reached", "request-fixture", 1000)
        for (failure in listOf(
            SpeechHttpFailure(429, details),
            AccountRequestFailure(details),
            SocketHandshakeFailure(429, details),
            ContinuousCaptureFailure(VoiceCapture.Failure.CONNECTION, SpeechHttpFailure(429, details)),
        )) {
            assertSame(details, speechFailureDetails(failure))
        }
        assertSame(details, VoiceCapture.State(errorDetails = details).errorDetails)
        assertSame(details, ReadAloudState(errorDetails = details).errorDetails)
    }

    @Test fun arbitraryExceptionsNeverBecomeUserVisibleDetails() {
        assertNull(speechFailureDetails(IOException("private transcript")))
        assertNull(speechFailureDetails(IllegalStateException("private audio URL")))
    }
}
