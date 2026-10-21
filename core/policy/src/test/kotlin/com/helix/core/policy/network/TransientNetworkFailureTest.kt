package com.helix.core.policy.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException

class TransientNetworkFailureTest {
    @Test fun classifiedConnectionLossCanBeConsideredOnlyAfterSafetyIsEstablished() {
        assertTrue(isTransientNetworkFailure(ConnectException("refused")))
        assertTrue(isTransientNetworkFailure(IOException("wrapper", EOFException())))
        assertFalse(isTransientNetworkFailure(IOException("network words alone are not evidence")))
        assertFalse(isTransientNetworkFailure(UnknownHostException("invalid host")))
    }

    @Test fun cancellationAndTlsFailureAreNotTransientRetriesEvenWithNestedSocketCauses() {
        val cancel = CancellationException().apply { initCause(ConnectException()) }
        val tls = SSLHandshakeException("certificate failure").apply { initCause(ConnectException()) }
        assertFalse(isTransientNetworkFailure(cancel))
        assertFalse(isTransientNetworkFailure(tls))
    }
}
