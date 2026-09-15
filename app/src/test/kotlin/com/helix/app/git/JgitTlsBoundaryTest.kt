package com.helix.app.git

import org.eclipse.jgit.transport.http.NoCheckX509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateException

/** Runs against the dependency artifact delivered to the app, not a test-only replacement. */
class JgitTlsBoundaryTest {
    @Test fun disablingVerificationRejectsBothCertificateDirections() {
        val manager = NoCheckX509TrustManager()
        assertEquals(0, manager.acceptedIssuers.size)
        assertThrows(CertificateException::class.java) { manager.checkServerTrusted(emptyArray(), "RSA") }
        assertThrows(CertificateException::class.java) { manager.checkClientTrusted(emptyArray(), "RSA") }
    }
}
