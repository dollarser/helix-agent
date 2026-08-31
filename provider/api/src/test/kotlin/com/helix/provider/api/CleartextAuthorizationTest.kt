package com.helix.provider.api

import com.helix.core.model.NormalizedEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-027: the LAN cleartext gate (provider doc 2.5) — authorization is bound to
 * host + port, never a global cleartext switch; https is always fine and a TLS failure
 * never silently downgrades to http (that is the stream contract's Error(TRANSPORT),
 * not a retry here).
 */
class CleartextAuthorizationTest {
    private val lanHost = NormalizedEndpoint.parse("http://192.168.1.50:11434/v1")
    private val emulatorBridge = NormalizedEndpoint.parse("http://10.0.2.2:11434/v1")
    private val loopback = NormalizedEndpoint.parse("http://127.0.0.1:8000/v1")
    private val httpsCloud = NormalizedEndpoint.parse("https://api.openai.com/v1")
    private val ipv6Lan = NormalizedEndpoint.parse("http://[fd00::1]:30000/v1")

    @Test
    fun httpsIsAlwaysPermittedWithoutAuthorization() {
        assertTrue(CleartextAuthorization.isPermitted(httpsCloud, emptySet()))
        assertNull(CleartextAuthorization.requiredFor(httpsCloud))
    }

    @Test
    fun httpWithoutAuthorizationIsDeniedFailClosed() {
        assertFalse(CleartextAuthorization.isPermitted(lanHost, emptySet()))
        assertFalse(CleartextAuthorization.isPermitted(emulatorBridge, emptySet()))
        // an authorization for a DIFFERENT host does not cover this one
        assertFalse(
            CleartextAuthorization.isPermitted(
                lanHost,
                setOf(CleartextAuthorization("192.168.1.51", 11434)),
            ),
        )
    }

    @Test
    fun httpIsPermittedOnlyForTheExactHostAndPort() {
        val exact = CleartextAuthorization("192.168.1.50", 11434)
        assertTrue(CleartextAuthorization.isPermitted(lanHost, setOf(exact)))
        // same host, different port: the binding is host AND port
        assertFalse(
            CleartextAuthorization.isPermitted(
                NormalizedEndpoint.parse("http://192.168.1.50:11435/v1"),
                setOf(exact),
            ),
        )
        // same port, different host
        assertFalse(CleartextAuthorization.isPermitted(emulatorBridge, setOf(exact)))
    }

    @Test
    fun multipleAuthorizationsEachBindTheirOwnHostPort() {
        val authorizations =
            setOf(
                CleartextAuthorization("192.168.1.50", 11434),
                CleartextAuthorization("10.0.2.2", 11434),
                CleartextAuthorization("127.0.0.1", 8000),
            )
        assertTrue(CleartextAuthorization.isPermitted(lanHost, authorizations))
        assertTrue(CleartextAuthorization.isPermitted(emulatorBridge, authorizations))
        assertTrue(CleartextAuthorization.isPermitted(loopback, authorizations))
        assertFalse(
            CleartextAuthorization.isPermitted(
                NormalizedEndpoint.parse("http://10.0.2.2:30000/v1"),
                authorizations,
            ),
        )
    }

    @Test
    fun ipv6LiteralsBindByTheirNormalizedHostForm() {
        // NormalizedEndpoint stores the IPv6 host without brackets
        assertEquals("fd00::1", ipv6Lan.host)
        val auth = CleartextAuthorization("fd00::1", 30000)
        assertTrue(CleartextAuthorization.isPermitted(ipv6Lan, setOf(auth)))
        assertFalse(
            CleartextAuthorization.isPermitted(
                NormalizedEndpoint.parse("http://[fd00::2]:30000/v1"),
                setOf(auth),
            ),
        )
    }

    @Test
    fun requiredForNamesTheExactAuthorizationForHttpOnly() {
        assertEquals(CleartextAuthorization("192.168.1.50", 11434), CleartextAuthorization.requiredFor(lanHost))
        assertEquals(CleartextAuthorization("127.0.0.1", 8000), CleartextAuthorization.requiredFor(loopback))
        assertNull(CleartextAuthorization.requiredFor(httpsCloud))
    }

    @Test
    fun authorizationConstructorFailsClosedOnBadInput() {
        try {
            CleartextAuthorization(" ", 11434)
            throw AssertionError("blank host must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            CleartextAuthorization("192.168.1.50", 0)
            throw AssertionError("port 0 must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
