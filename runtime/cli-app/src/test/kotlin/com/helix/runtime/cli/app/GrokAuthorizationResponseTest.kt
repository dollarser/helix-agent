package com.helix.runtime.cli.app

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class GrokAuthorizationResponseTest {
    @Test
    fun missingRefreshIsRejectedBeforeMalformedExpiryIsRead() {
        val bytes = response(refresh = null, expiry = "not-a-number")
        assertEquals(GrokDevicePoll.Rejected("missing_refresh_token"), decode(bytes))
    }

    @Test
    fun invalidExpiryCannotCreateAuthorizedSession() {
        listOf(null, "0", "-1", "not-a-number").forEach { expiry ->
            assertEquals(GrokDevicePoll.Rejected("invalid_expiry"), decode(response(expiry = expiry)))
        }
    }

    @Test
    fun expiryOverflowCannotWrapIntoAuthorizedSession() {
        assertThrows(ArithmeticException::class.java) { decode(response(expiry = Long.MAX_VALUE.toString())) }
    }

    private fun decode(bytes: ByteArray) = GrokDeviceProtocol.decodePoll(bytes, 5_000, 1_000)

    private fun response(
        refresh: String? = "fixture-refresh",
        expiry: String?,
    ): ByteArray {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"tier":3}""".encodeToByteArray())
        return buildJsonObject {
            put("access_token", "header.$payload.signature")
            if (refresh != null) put("refresh_token", refresh)
            if (expiry != null) put("expires_in", expiry)
        }.toString().encodeToByteArray()
    }
}
