package com.helix.runtime.cli.app

import okio.Pipe
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors

class CodexDeviceOAuthTest {
    @Test fun officialEndpointsAndAttemptAreStrict() {
        assertEquals(
            "https://auth.openai.com/api/accounts/deviceauth/usercode",
            CodexDeviceProtocol.USER_CODE_URL,
        )
        assertEquals("https://auth.openai.com/codex/device", CodexDeviceProtocol.VERIFICATION_URL)
        val attempt =
            CodexDeviceProtocol.decodeAttempt(
                """{"device_auth_id":"attempt","user_code":"ABCD-1234","interval":"5"}""".encodeToByteArray(),
                1_000,
            )
        assertEquals(5_000, attempt.intervalMillis)
        assertEquals(901_000, attempt.expiresAtEpochMillis)
        assertThrows(IllegalArgumentException::class.java) {
            CodexDeviceProtocol.decodeAttempt(
                """{"device_auth_id":"attempt","user_code":"bad!","interval":"5"}""".encodeToByteArray(),
                0,
            )
        }
    }

    @Test fun pollRequiresMatchingServerPkcePair() {
        val verifier = "verifier-from-server"
        val challenge =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()),
            )
        assertEquals(
            CodexDevicePoll.Authorized(CodexDeviceAuthorization("authorization", verifier)),
            CodexDeviceProtocol.decodePoll(
                """{"authorization_code":"authorization","code_verifier":"$verifier","code_challenge":"$challenge"}"""
                    .encodeToByteArray(),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CodexDeviceProtocol.decodePoll(
                """{"authorization_code":"authorization","code_verifier":"$verifier","code_challenge":"wrong"}"""
                    .encodeToByteArray(),
            )
        }
    }

    @Test fun boundedDeviceJsonDoesNotWaitForConnectionEof() {
        val pipe = Pipe(8 * 1024L)
        val sink = pipe.sink.buffer()
        val source = pipe.source.buffer()
        val writer = Executors.newSingleThreadExecutor()
        try {
            writer
                .submit {
                    sink.write("""{"authorization_code":"ready"}""".encodeToByteArray())
                    sink.flush()
                }.get()
            assertEquals(
                """{"authorization_code":"ready"}""",
                source.readBoundedJsonObjectByteArray(1_024).decodeToString(),
            )
        } finally {
            sink.close()
            source.close()
            writer.shutdownNow()
        }
    }

    @Test fun controllerPollsThenStoresOnlyAfterExchange() {
        val store = MemoryStore()
        val attempt = attempt()
        var polls = 0
        val transport =
            object : CodexDeviceTransport {
                override fun requestDeviceCode() = attempt

                override fun poll(attempt: CodexDeviceAttempt): CodexDevicePoll =
                    if (polls++ == 0) CodexDevicePoll.Pending else CodexDevicePoll.Authorized(authorization())

                override fun exchange(
                    attempt: CodexDeviceAttempt,
                    authorization: CodexDeviceAuthorization,
                ) = CliSubscriptionSession("access", "refresh", "id", 10_000, "account")
            }
        CodexDeviceLoginController(CliSubscriptionCredentialVault(store), transport, { 0 }, {}).finish(
            attempt,
            DeviceLoginCancellation(),
        )
        assertEquals(2, polls)
        assertTrue(CliSubscriptionCredentialVault(store).contains(CliSubscriptionProvider.CODEX))
    }

    @Test fun controllerRetriesAmbiguousPollTransportFailure() {
        val store = MemoryStore()
        var polls = 0
        val transport =
            object : CodexDeviceTransport {
                override fun requestDeviceCode() = attempt()

                override fun poll(attempt: CodexDeviceAttempt): CodexDevicePoll {
                    if (polls++ == 0) {
                        throw CodexDeviceNetworkException("poll", java.net.SocketTimeoutException("test"))
                    }
                    return CodexDevicePoll.Authorized(authorization())
                }

                override fun exchange(
                    attempt: CodexDeviceAttempt,
                    authorization: CodexDeviceAuthorization,
                ) = CliSubscriptionSession("access", "refresh", "id", 10_000, "account")
            }
        CodexDeviceLoginController(CliSubscriptionCredentialVault(store), transport, { 0 }, {}).finish(
            attempt(),
            DeviceLoginCancellation(),
        )
        assertEquals(2, polls)
        assertTrue(CliSubscriptionCredentialVault(store).contains(CliSubscriptionProvider.CODEX))
    }

    @Test fun expiryAndExchangeFailureNeverStoreCredential() {
        val store = MemoryStore()
        val transport =
            object : CodexDeviceTransport {
                override fun requestDeviceCode() = attempt()

                override fun poll(attempt: CodexDeviceAttempt) = CodexDevicePoll.Authorized(authorization())

                override fun exchange(
                    attempt: CodexDeviceAttempt,
                    authorization: CodexDeviceAuthorization,
                ): CliSubscriptionSession = error("exchange failed")
            }
        assertThrows(CodexDeviceLoginException::class.java) {
            CodexDeviceLoginController(CliSubscriptionCredentialVault(store), transport, { 60_000 }, {}).finish(
                attempt(),
                DeviceLoginCancellation(),
            )
        }
        assertFalse(CliSubscriptionCredentialVault(store).contains(CliSubscriptionProvider.CODEX))

        assertThrows(IllegalStateException::class.java) {
            CodexDeviceLoginController(CliSubscriptionCredentialVault(store), transport, { 0 }, {}).finish(
                attempt(),
                DeviceLoginCancellation(),
            )
        }
        assertFalse(CliSubscriptionCredentialVault(store).contains(CliSubscriptionProvider.CODEX))
    }

    private fun attempt() =
        CodexDeviceAttempt("attempt", "ABCD-1234", CodexDeviceProtocol.VERIFICATION_URL, 5_000, 50_000)

    private fun authorization() = CodexDeviceAuthorization("authorization", "verifier")

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
