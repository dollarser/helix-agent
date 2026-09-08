package com.helix.runtime.cli.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class GrokDeviceOAuthTest {
    @Test fun fixedIdentityAndAttemptAreStrict() {
        assertEquals("b1a00492-073a-47ea-816f-4c329264a828", GrokDeviceProtocol.CLIENT_ID)
        val attempt =
            GrokDeviceProtocol.decodeAttempt(
                (
                    "{\"device_code\":\"device\",\"user_code\":\"ABCD-1234\",\"verification_uri" +
                        "\":\"https://accounts.x.ai/device\",\"verification_uri_complete\":\"htt" +
                        "ps://accounts.x.ai/device?code=ABCD-1234\",\"expires_in\":600,\"inter" +
                        "val\":2}"
                ).encodeToByteArray(),
                1_000,
            )
        assertEquals(5_000, attempt.intervalMillis)
        assertEquals(601_000, attempt.expiresAtEpochMillis)
        assertThrows(IllegalArgumentException::class.java) {
            GrokDeviceProtocol.decodeAttempt(
                """{"device_code":"d","user_code":"bad!","verification_uri":"https://evil.test","expires_in":10}"""
                    .encodeToByteArray(),
                0,
            )
        }
    }

    @Test fun pendingSlowDownAndRejectionAreTyped() {
        assertEquals(GrokDevicePoll.Pending(5_000), pollError("authorization_pending", 5_000))
        assertEquals(GrokDevicePoll.Pending(10_000), pollError("slow_down", 5_000))
        assertEquals(GrokDevicePoll.Rejected("access_denied"), pollError("access_denied", 5_000))
    }

    @Test fun onlyKnownPaidTierCreatesAuthorizedSession() {
        listOf(1, 3, 4, 5, 6, 7).forEach { tier ->
            assertTrue(GrokDeviceProtocol.decodePoll(tokenResponse(tier), 5_000, 1_000) is GrokDevicePoll.Authorized)
        }
        assertEquals(
            GrokDevicePoll.Rejected("ineligible_tier"),
            GrokDeviceProtocol.decodePoll(tokenResponse(0), 5_000, 1_000),
        )
        assertEquals(
            GrokDevicePoll.Rejected("ineligible_tier"),
            GrokDeviceProtocol.decodePoll(tokenResponse(2), 5_000, 1_000),
        )
        assertEquals(
            GrokDevicePoll.Rejected("unknown_tier"),
            GrokDeviceProtocol.decodePoll(tokenResponse(null), 5_000, 1_000),
        )
    }

    @Test fun controllerRetriesTransientPollAndNeverStoresRejectedTier() {
        val store = MemoryStore()
        var polls = 0
        var now = 0L
        val transport =
            object : GrokDeviceTransport {
                override fun requestDeviceCode() = attempt()

                override fun poll(
                    attempt: GrokDeviceAttempt,
                    intervalMillis: Long,
                ): GrokDevicePoll {
                    if (polls++ == 0) throw IOException("transient")
                    return GrokDevicePoll.Rejected("ineligible_tier")
                }

                override fun refresh(session: CliSubscriptionSession) = session
            }
        val controller = GrokLoginController(CliSubscriptionCredentialVault(store), transport, { now }, { now += it })
        val failure =
            assertThrows(
                GrokDeviceLoginException::class.java,
            ) { controller.finish(controller.start(), DeviceLoginCancellation()) }
        assertEquals("ineligible_tier", failure.reason)
        assertEquals(2, polls)
        assertFalse(CliSubscriptionCredentialVault(store).contains(CliSubscriptionProvider.GROK))
    }

    @Test fun controllerStoresPaidTierAndLogoutDeletesIt() {
        val store = MemoryStore()
        val session = CliSubscriptionSession("access", "refresh", null, 10_000)
        val transport = transportWith(GrokDevicePoll.Authorized(session, 3))
        val vault = CliSubscriptionCredentialVault(store)
        val controller = GrokLoginController(vault, transport, { 0 }, {})
        assertEquals(3, controller.finish(controller.start(), DeviceLoginCancellation()))
        assertTrue(vault.contains(CliSubscriptionProvider.GROK))
        controller.logout()
        assertFalse(vault.contains(CliSubscriptionProvider.GROK))
    }

    @Test fun refreshRotatesAndIneligibleRotationDeletesCredential() {
        val store = MemoryStore()
        val vault = CliSubscriptionCredentialVault(store)
        val initial = CliSubscriptionSession("access", "refresh", null, 10_000)
        vault.save(CliSubscriptionProvider.GROK, initial)
        val bad =
            object : GrokDeviceTransport {
                override fun requestDeviceCode() = attempt()

                override fun poll(
                    attempt: GrokDeviceAttempt,
                    intervalMillis: Long,
                ) = GrokDevicePoll.Pending(intervalMillis)

                override fun refresh(session: CliSubscriptionSession): CliSubscriptionSession =
                    throw IllegalArgumentException("tier")
            }
        assertThrows(IllegalArgumentException::class.java) { GrokLoginController(vault, bad).refresh() }
        assertFalse(vault.contains(CliSubscriptionProvider.GROK))
    }

    private fun pollError(
        code: String,
        interval: Long,
    ) = GrokDeviceProtocol.decodePoll("""{"error":"$code"}""".encodeToByteArray(), interval, 0)

    private fun tokenResponse(tier: Int?): ByteArray {
        val claim = if (tier == null) "{}" else """{"tier":$tier}"""
        val token = "header.${Base64.getUrlEncoder().withoutPadding().encodeToString(
            claim.encodeToByteArray(),
        )}.signature"
        return """{"access_token":"$token","refresh_token":"refresh","expires_in":3600}""".encodeToByteArray()
    }

    private fun attempt() = GrokDeviceAttempt("device", "CODE", "https://accounts.x.ai/device", null, 60_000, 5_000)

    private fun transportWith(result: GrokDevicePoll) =
        object : GrokDeviceTransport {
            override fun requestDeviceCode() = attempt()

            override fun poll(
                attempt: GrokDeviceAttempt,
                intervalMillis: Long,
            ) = result

            override fun refresh(session: CliSubscriptionSession) = session
        }

    private class MemoryStore : CliSecretStore {
        private val values = mutableMapOf<String, String>()

        override fun put(
            name: String,
            value: String,
        ) {
            values[name] = value
        }

        override fun get(name: String) = values.getValue(name)

        override fun delete(name: String) {
            values.remove(name)
        }

        override fun contains(name: String) = values.containsKey(name)
    }
}
