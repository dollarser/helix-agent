package com.helix.runtime.cli.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AntigravityLoginDiagnosticTest {
    @Test fun diagnosticNeverIncludesExceptionContent() {
        assertEquals("NETWORK", AntigravityLoginDiagnostic.code(java.io.IOException("private response")))
        assertEquals("DNS", AntigravityLoginDiagnostic.code(java.net.UnknownHostException("private host")))
        assertEquals("HTTP_403_PROJECT", AntigravityLoginDiagnostic.code(AntigravityHttpException(403, "private path")))
        assertEquals("HTTP_400_TOKEN", AntigravityLoginDiagnostic.code(AntigravityHttpException(400, "token")))
        assertEquals("INTERNAL", AntigravityLoginDiagnostic.code(Exception("private token")))
    }
}
