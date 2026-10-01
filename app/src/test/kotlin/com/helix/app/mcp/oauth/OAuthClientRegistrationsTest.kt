package com.helix.app.mcp.oauth

import com.helix.extensions.mcp.McpEndpointGate
import com.helix.extensions.mcp.McpNetworkPermit
import com.helix.extensions.mcp.oauth.McpOAuthClientRegistration
import com.helix.extensions.mcp.oauth.McpOAuthServerMetadata
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@Suppress("MaxLineLength") // Exact bounded JSON response fixtures, not runtime payload generation.
class OAuthClientRegistrationsTest {
    @Test fun explicitPreregisteredClientDoesNotRegisterAndCannotCrossIssuer() =
        runBlocking {
            val fixture = Fixture()
            val identity = fixture.clients.resolve("server", metadata(), REDIRECT, "preregistered-id")
            assertEquals("preregistered", identity.source)
            assertEquals(0, fixture.requests.get())
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    fixture.reopen().resolve(
                        "server",
                        metadata().copy(issuer = "$ISSUER/other"),
                        REDIRECT,
                        "preregistered-id",
                    )
                }
            }
            assertEquals(0, fixture.requests.get())
        }

    @Test fun successfulRegistrationSurvivesReopenWithoutRepeatingPost() =
        runBlocking {
            val fixture = Fixture()
            val registered = fixture.clients.register("server", metadata(), REDIRECT)
            assertEquals("registered-fixture", registered.clientId)
            val later = fixture.reopen()
            assertEquals(registered, later.remembered(metadata(), REDIRECT))
            assertEquals(registered, later.resolve("server", metadata(), REDIRECT, ""))
            assertEquals(1, fixture.requests.get())
            assertTrue(fixture.secrets.aliases().all { it.value.startsWith("oauth.client.") })
            assertThrows(
                IllegalStateException::class.java,
            ) { runBlocking { later.register("server", metadata(), REDIRECT) } }
            assertEquals(1, fixture.requests.get())
        }

    @Test fun failedOrLostRegistrationIsDurableAndNeverSilentlyRetried() {
        val fixture = Fixture()
        fixture.status = 503
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { fixture.clients.register("server", metadata(), REDIRECT) }
        }
        assertThrows(IllegalStateException::class.java) { fixture.reopen().remembered(metadata(), REDIRECT) }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.reopen().register("server", metadata(), REDIRECT) }
        }
        assertEquals(1, fixture.requests.get())
        fixture.clients.forget("server", ISSUER, REDIRECT)
        fixture.status = 200
        assertEquals(
            "registered-fixture",
            runBlocking { fixture.clients.register("server", metadata(), REDIRECT) }.clientId,
        )
        assertEquals(2, fixture.requests.get())
    }

    @Test fun forgetDuringRegistrationRejectsLatePublication() {
        val fixture = Fixture()
        fixture.onRequest = { fixture.clients.forget("server", ISSUER, REDIRECT) }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.clients.register("server", metadata(), REDIRECT) }
        }
        assertNull(fixture.reopen().remembered(metadata(), REDIRECT))
        assertEquals(1, fixture.requests.get())
    }

    @Test fun forgetDuringCimdReadDoesNotRestoreForgottenConfiguration() {
        val fixture = Fixture()
        fixture.body = document(DOCUMENT)
        fixture.onRequest = { fixture.clients.forget("server", ISSUER, REDIRECT) }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.clients.useDocument("server", metadata(true), REDIRECT, DOCUMENT) }
        }
        assertNull(fixture.reopen().remembered(metadata(true), REDIRECT))
    }

    @Test fun knownCimdIsRevalidatedBeforeEachLoginAndCannotDowngradeToPreregistered() =
        runBlocking {
            val fixture = Fixture()
            fixture.body = document(DOCUMENT)
            fixture.clients.useDocument("server", metadata(true), REDIRECT, DOCUMENT)
            assertEquals(DOCUMENT, fixture.reopen().resolve("server", metadata(true), REDIRECT, DOCUMENT).clientId)
            assertEquals(2, fixture.requests.get())
            fixture.body = document("changed-id")
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { fixture.reopen().resolve("server", metadata(true), REDIRECT, DOCUMENT) }
            }
            assertEquals(3, fixture.requests.get())
        }

    @Test fun newerSelectionWinsAgainstLateCimdValidation() =
        runBlocking<Unit> {
            val fixture = Fixture()
            fixture.body = document(DOCUMENT)
            fixture.clients.useDocument("server", metadata(true), REDIRECT, DOCUMENT)
            fixture.onRequest = {
                runBlocking { fixture.reopen().resolve("server", metadata(true), REDIRECT, "new-preregistered-id") }
            }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { fixture.clients.resolve("server", metadata(true), REDIRECT, DOCUMENT) }
            }
            fixture.onRequest = {}
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    fixture.reopen().resolve(
                        "server",
                        metadata().copy(issuer = "$ISSUER/other"),
                        REDIRECT,
                        "new-preregistered-id",
                    )
                }
            }
        }

    @Test fun issuerAndRedirectHaveSeparatePersistentRegistrationKeys() =
        runBlocking {
            val fixture = Fixture()
            fixture.clients.register("server", metadata(), REDIRECT)
            assertNull(fixture.clients.remembered(metadata().copy(issuer = "$ISSUER/other"), REDIRECT))
            assertNull(fixture.clients.remembered(metadata(), "helix://oauth/callback"))
            assertEquals(1, fixture.requests.get())
        }

    private class Fixture {
        val secrets = OAuthTestSecrets()
        val requests = AtomicInteger()
        var status = 200
        var body = document("registered-fixture")
        var onRequest: () -> Unit = {}
        private val gate = McpEndpointGate { McpNetworkPermit(it.host, listOf(byteArrayOf(127, 0, 0, 1))) }
        private val http =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests.incrementAndGet()
                    onRequest()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("fixture")
                        .body(body.toResponseBody())
                        .build()
                }.build()
        val clients = reopen()

        fun reopen() = OAuthClientRegistrations(secrets, McpOAuthClientRegistration(gate, http))
    }

    private companion object {
        const val ISSUER = "https://issuer.example"
        const val REDIRECT = "helix://oauth/mcp/callback"
        const val DOCUMENT = "https://client.example/client.json"

        fun metadata(cimd: Boolean = false) =
            McpOAuthServerMetadata(
                ISSUER,
                "$ISSUER/authorize",
                "$ISSUER/token",
                registrationEndpoint = "$ISSUER/register",
                codeChallengeMethodsSupported = listOf("S256"),
                clientIdMetadataDocumentSupported = cimd,
            )

        fun document(id: String) =
            """{"client_id":"$id","client_name":"Fixture","redirect_uris":["$REDIRECT"],"token_endpoint_auth_method":"none","response_types":["code"]}"""
    }
}
