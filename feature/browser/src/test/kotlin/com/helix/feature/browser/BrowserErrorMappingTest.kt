package com.helix.feature.browser

import android.webkit.WebViewClient
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
    fun platformCodesMapWithoutConfusingChromiumValues() {
        val expected =
            mapOf(
                WebViewClient.ERROR_HOST_LOOKUP to BrowserErrorKind.HOST_LOOKUP_FAILED,
                WebViewClient.ERROR_TIMEOUT to BrowserErrorKind.TIMEOUT,
                WebViewClient.ERROR_FAILED_SSL_HANDSHAKE to BrowserErrorKind.SSL,
                WebViewClient.ERROR_CONNECT to BrowserErrorKind.CONNECTION_FAILED,
                WebViewClient.ERROR_IO to BrowserErrorKind.CONNECTION_FAILED,
                WebViewClient.ERROR_REDIRECT_LOOP to BrowserErrorKind.CONNECTION_FAILED,
                WebViewClient.ERROR_AUTHENTICATION to BrowserErrorKind.CONNECTION_FAILED,
                WebViewClient.ERROR_PROXY_AUTHENTICATION to BrowserErrorKind.CONNECTION_FAILED,
                WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME to BrowserErrorKind.CONNECTION_FAILED,
                WebViewClient.ERROR_TOO_MANY_REQUESTS to BrowserErrorKind.CONNECTION_FAILED,
            )
        for ((code, kind) in expected) {
            assertEquals(code.toString(), kind, BrowserErrorMapping.map(0, code))
        }
    }

    @Test
    fun otherPlatformCodesDoNotInventDnsOrTlsFailures() {
        for (code in listOf(
            WebViewClient.ERROR_UNKNOWN,
            WebViewClient.ERROR_BAD_URL,
            WebViewClient.ERROR_UNSUPPORTED_SCHEME,
            WebViewClient.ERROR_FILE,
            WebViewClient.ERROR_FILE_NOT_FOUND,
            -20,
            -21,
            -42,
        )) {
            assertEquals(code.toString(), BrowserErrorKind.UNKNOWN, BrowserErrorMapping.map(0, code))
        }
    }
}
