package com.helix.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserErrorMappingTest {
    @Test
    fun zeroCodesAreNotFailures() {
        assertNull(BrowserErrorMapping.map(netError = 0, clientError = 0))
    }

    @Test
    fun anAbortedLoadIsNotAnError() {
        assertNull(BrowserErrorMapping.map(netError = -3, clientError = 0))
    }

    @Test
    fun theChromiumCodeWinsOverTheLegacyCode() {
        assertEquals(
            BrowserErrorKind.HOST_LOOKUP_FAILED,
            BrowserErrorMapping.map(netError = -105, clientError = -7),
        )
    }

    @Test
    fun dnsFailuresMapToHostLookup() {
        assertEquals(BrowserErrorKind.HOST_LOOKUP_FAILED, BrowserErrorMapping.map(-105, 0))
    }

    @Test
    fun timeoutCodesMapToTimeout() {
        assertEquals(BrowserErrorKind.TIMEOUT, BrowserErrorMapping.map(-7, 0))
        assertEquals(BrowserErrorKind.TIMEOUT, BrowserErrorMapping.map(-118, 0))
    }

    @Test
    fun sslAndCertificateCodesMapToSsl() {
        for (code in listOf(-107, -113, -200, -201, -202, -207, -220)) {
            assertEquals(code.toString(), BrowserErrorKind.SSL, BrowserErrorMapping.map(code, 0))
        }
    }

    @Test
    fun connectionLevelCodesMapToConnectionFailed() {
        for (code in listOf(-15, -21, -100, -101, -102, -103, -106, -108, -109, -111, -115, -130, -310)) {
            assertEquals(code.toString(), BrowserErrorKind.CONNECTION_FAILED, BrowserErrorMapping.map(code, 0))
        }
    }

    @Test
    fun unknownChromiumCodesMapToUnknown() {
        assertEquals(BrowserErrorKind.UNKNOWN, BrowserErrorMapping.map(-2, 0))
        assertEquals(BrowserErrorKind.UNKNOWN, BrowserErrorMapping.map(-999, 0))
        assertEquals(BrowserErrorKind.UNKNOWN, BrowserErrorMapping.map(-300, 0))
    }

    @Test
    fun legacyClientCodesStillMapWhenNoChromiumCodeIsPresent() {
        assertEquals(BrowserErrorKind.TIMEOUT, BrowserErrorMapping.map(0, -8))
        assertEquals(BrowserErrorKind.SSL, BrowserErrorMapping.map(0, -9))
        assertEquals(BrowserErrorKind.HOST_LOOKUP_FAILED, BrowserErrorMapping.map(0, -3))
        assertEquals(BrowserErrorKind.HOST_LOOKUP_FAILED, BrowserErrorMapping.map(0, -20))
        assertEquals(BrowserErrorKind.HOST_LOOKUP_FAILED, BrowserErrorMapping.map(0, -21))
        assertEquals(BrowserErrorKind.CONNECTION_FAILED, BrowserErrorMapping.map(0, -4))
        assertEquals(BrowserErrorKind.CONNECTION_FAILED, BrowserErrorMapping.map(0, -7))
        assertEquals(BrowserErrorKind.CONNECTION_FAILED, BrowserErrorMapping.map(0, -13))
        assertEquals(BrowserErrorKind.CONNECTION_FAILED, BrowserErrorMapping.map(0, -15))
        assertEquals(BrowserErrorKind.UNKNOWN, BrowserErrorMapping.map(0, -1))
        assertEquals(BrowserErrorKind.UNKNOWN, BrowserErrorMapping.map(0, -42))
    }
}
