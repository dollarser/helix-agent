@file:Suppress("ktlint:standard:max-line-length") // JSON wire fixtures are clearer when kept on one line.

package com.helix.runtime.cli.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CopilotDeviceOAuthTest {
    @Test fun fixedIdentityAndDeviceAttemptAreStrict() {
        assertEquals("Iv1.b507a08c87ecfe98", CopilotDeviceProtocol.CLIENT_ID)
        val attempt =
            CopilotDeviceProtocol.decodeAttempt(
                (
                    """{"device_code":"device","user_code":"ABCD-EFGH",""" +
                        """"verification_uri":"https://github.com/login/device",""" +
                        """"expires_in":900,"interval":2}"""
                ).encodeToByteArray(),
                1_000,
            )
        assertEquals(5_000, attempt.intervalMillis)
        assertEquals(901_000, attempt.expiresAtEpochMillis)
        assertThrows(IllegalArgumentException::class.java) {
            CopilotDeviceProtocol.decodeAttempt(
                (
                    """{"device_code":"d","user_code":"u","verification_uri":"http://example.test",""" +
                        """"expires_in":10}"""
                ).encodeToByteArray(),
                0,
            )
        }
    }

    @Test fun pendingAndSlowDownRespectRequiredIntervals() {
        assertEquals(
            CopilotDevicePoll.Pending(5_000),
            CopilotDeviceProtocol.decodePoll("""{"error":"authorization_pending"}""".encodeToByteArray(), 5_000),
        )
        assertEquals(
            CopilotDevicePoll.Pending(10_000),
            CopilotDeviceProtocol.decodePoll("""{"error":"slow_down"}""".encodeToByteArray(), 5_000),
        )
        assertEquals(
            CopilotDevicePoll.Rejected("access_denied"),
            CopilotDeviceProtocol.decodePoll("""{"error":"access_denied"}""".encodeToByteArray(), 5_000),
        )
    }

    @Test fun successfulExchangeKeepsBothTokensInsideSession() {
        val session =
            CopilotDeviceProtocol.decodeCopilotSession(
                """{"token":"copilot-token","expires_at":200}""".encodeToByteArray(),
                "github-token",
                100_000,
            )
        assertEquals("copilot-token", session.accessToken)
        assertEquals("github-token", session.refreshToken)
        assertEquals(200_000, session.expiresAtEpochMillis)
    }

    @Test fun controllerAppliesSlowDownThenStoresOnlyAfterEntitlementExchange() {
        val store = MemoryStore()
        val sleeps = mutableListOf<Long>()
        var now = 0L
        var polls = 0
        val transport =
            object : CopilotDeviceTransport {
                override fun requestDeviceCode() = attempt()

                override fun poll(
                    attempt: CopilotDeviceAttempt,
                    intervalMillis: Long,
                ): CopilotDevicePoll =
                    if (polls++ == 0) {
                        CopilotDevicePoll.Pending(intervalMillis + 5_000)
                    } else {
                        CopilotDevicePoll.Authorized("github")
                    }

                override fun exchange(githubToken: String) =
                    CliSubscriptionSession("copilot", githubToken, null, 50_000)
            }
        val controller =
            CopilotLoginController(
                CliSubscriptionCredentialVault(store),
                transport,
                { now },
                {
                    sleeps += it
                    now += it
                },
            )
        val started = controller.start()
        val cancellation = CopilotLoginCancellation()
        controller.finish(started, cancellation)
        assertEquals(listOf(5_000L, 10_000L), sleeps)
        val stored =
            CliSubscriptionCredentialVault(store)
                .load(CliSubscriptionProvider.COPILOT)
        assertEquals("github", stored.refreshToken)
    }

    @Test fun rejectionExpiryCancellationAndExchangeFailureNeverStoreCredential() {
        listOf<CopilotDeviceTransport>(
            transportWith(CopilotDevicePoll.Rejected("access_denied")),
            object : CopilotDeviceTransport {
                override fun requestDeviceCode() = attempt(expiresAt = 1)

                override fun poll(
                    attempt: CopilotDeviceAttempt,
                    intervalMillis: Long,
                ) = CopilotDevicePoll.Authorized("github")

                override fun exchange(githubToken: String) = error("must not exchange")
            },
            object : CopilotDeviceTransport {
                override fun requestDeviceCode() = attempt()

                override fun poll(
                    attempt: CopilotDeviceAttempt,
                    intervalMillis: Long,
                ) = CopilotDevicePoll.Authorized("github")

                override fun exchange(githubToken: String): CliSubscriptionSession =
                    throw CopilotOAuthEndpointException(403)
            },
        ).forEachIndexed { index, transport ->
            val store = MemoryStore()
            val controller =
                CopilotLoginController(
                    CliSubscriptionCredentialVault(store),
                    transport,
                    { if (index == 1) 2 else 0 },
                    {},
                )
            assertThrows(IllegalStateException::class.java) {
                controller.finish(controller.start(), CopilotLoginCancellation())
            }
            assertFalse(CliSubscriptionCredentialVault(store).contains(CliSubscriptionProvider.COPILOT))
        }

        val store = MemoryStore()
        val cancellation = CopilotLoginCancellation().also { it.cancel() }
        val controller =
            CopilotLoginController(
                CliSubscriptionCredentialVault(store),
                transportWith(CopilotDevicePoll.Authorized("github")),
                { 0 },
                {},
            )
        val started = controller.start()
        assertThrows(IllegalStateException::class.java) {
            controller.finish(started, cancellation)
        }
        assertFalse(CliSubscriptionCredentialVault(store).contains(CliSubscriptionProvider.COPILOT))
    }

    @Test fun logoutDeletesCopilotCredential() {
        val store = MemoryStore()
        val vault = CliSubscriptionCredentialVault(store)
        vault.save(CliSubscriptionProvider.COPILOT, CliSubscriptionSession("a", "r", null, 1))
        CopilotLoginController(vault, transportWith(CopilotDevicePoll.Rejected("unused"))).logout()
        assertFalse(vault.contains(CliSubscriptionProvider.COPILOT))
    }

    @Test fun transientEntitlementExchangeIsRetriedWithoutRepeatingDeviceAuthorization() {
        val store = MemoryStore()
        var exchanges = 0
        val transport =
            object : CopilotDeviceTransport {
                override fun requestDeviceCode() = attempt()

                override fun poll(
                    attempt: CopilotDeviceAttempt,
                    intervalMillis: Long,
                ) = CopilotDevicePoll.Authorized("github")

                override fun exchange(githubToken: String): CliSubscriptionSession {
                    if (exchanges++ == 0) throw IOException("transient")
                    return CliSubscriptionSession("copilot", githubToken, null, 10_000)
                }
            }
        val vault = CliSubscriptionCredentialVault(store)
        val controller = CopilotLoginController(vault, transport, { 0 }, {})

        controller.finish(controller.start(), CopilotLoginCancellation())

        assertEquals(2, exchanges)
        assertTrue(vault.contains(CliSubscriptionProvider.COPILOT))
    }

    @Test fun transientPollingFailurePreservesTheSameDeviceAuthorizationAttempt() {
        val store = MemoryStore()
        var polls = 0
        var exchanges = 0
        var now = 0L
        val transport =
            object : CopilotDeviceTransport {
                override fun requestDeviceCode() = attempt()

                override fun poll(
                    attempt: CopilotDeviceAttempt,
                    intervalMillis: Long,
                ): CopilotDevicePoll {
                    if (polls++ == 0) throw IOException("transient")
                    return CopilotDevicePoll.Authorized("github")
                }

                override fun exchange(githubToken: String): CliSubscriptionSession {
                    exchanges += 1
                    return CliSubscriptionSession("copilot", githubToken, null, 20_000)
                }
            }
        val vault = CliSubscriptionCredentialVault(store)
        val controller = CopilotLoginController(vault, transport, { now }, { now += it })

        val started = controller.start()
        controller.finish(started, CopilotLoginCancellation())

        assertEquals(2, polls)
        assertEquals(1, exchanges)
        assertTrue(vault.contains(CliSubscriptionProvider.COPILOT))
    }

    private fun attempt(expiresAt: Long = 60_000) =
        CopilotDeviceAttempt("device", "code", "https://github.com/login/device", expiresAt, 5_000)

    private fun transportWith(result: CopilotDevicePoll) =
        object : CopilotDeviceTransport {
            override fun requestDeviceCode() = attempt()

            override fun poll(
                attempt: CopilotDeviceAttempt,
                intervalMillis: Long,
            ) = result

            override fun exchange(githubToken: String) = CliSubscriptionSession("copilot", githubToken, null, 10_000)
        }

    private class MemoryStore : CliSecretStore {
        private val values = mutableMapOf<String, String>()

        override fun put(
            name: String,
            value: String,
        ) {
            values[name] = value
        }

        override fun get(name: String): String = values.getValue(name)

        override fun delete(name: String) {
            values.remove(name)
        }

        override fun contains(name: String): Boolean = values.containsKey(name)
    }
}
