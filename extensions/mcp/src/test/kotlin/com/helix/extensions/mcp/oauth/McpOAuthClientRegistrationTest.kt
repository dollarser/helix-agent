package com.helix.extensions.mcp.oauth

import com.helix.core.model.NormalizedEndpoint
import com.helix.extensions.mcp.McpEndpointGate
import com.helix.extensions.mcp.McpNetworkPermit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("MaxLineLength") // Exact protocol JSON fixtures are kept verbatim; production limits remain tested.
class McpOAuthClientRegistrationTest {
    @Test fun validCimdRequiresExactIdentityAndRedirectWithoutCredentials() =
        runBlocking {
            val fixture = Fixture()
            val identity = fixture.registration.validateDocument(metadata(true), DOCUMENT, REDIRECT)
            assertEquals(DOCUMENT, identity.clientId)
            assertEquals("cimd", identity.source)
            assertEquals("GET", fixture.requests.single().method)
            assertEquals(null, fixture.requests.single().header("Authorization"))
        }

    @Test fun malformedCimdIsNotUsedAndDoesNotFallBackToPost() {
        val fixture = Fixture()
        listOf(
            document().replace(DOCUMENT, "$DOCUMENT/other"),
            document().replace(REDIRECT, "helix://oauth/wrong"),
            document().replace("\"none\"", "\"client_secret_basic\""),
            document().dropLast(1) + ",\"client_secret\":\"fixture-only\"}",
            "x".repeat(65537),
        ).forEach { body ->
            fixture.body = body
            assertThrows(Exception::class.java) {
                runBlocking { fixture.registration.validateDocument(metadata(true), DOCUMENT, REDIRECT) }
            }
        }
        assertTrue(fixture.requests.all { it.method == "GET" })
    }

    @Test fun invalidDocumentUrlAndUnsupportedServerAreRejectedBeforeNetwork() {
        val fixture = Fixture()
        listOf(
            "http://client.example/meta.json",
            "https://client.example",
            "https://client.example/",
            "$DOCUMENT#fragment",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { fixture.registration.validateDocument(metadata(true), url, REDIRECT) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { fixture.registration.validateDocument(metadata(false), DOCUMENT, REDIRECT) }
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun explicitDcrDeclaresNativePublicClientAndReturnsBoundIdentity() =
        runBlocking {
            val fixture = Fixture()
            fixture.body = document("registered-id")
            val identity = fixture.registration.register(metadata(false), REDIRECT)
            assertEquals("registered-id", identity.clientId)
            assertEquals(ISSUER, identity.issuer)
            val request = fixture.requests.single()
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            val sent = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals("native", sent.getValue("application_type").jsonPrimitive.content)
            assertEquals("none", sent.getValue("token_endpoint_auth_method").jsonPrimitive.content)
            assertEquals("POST", request.method)
            assertFalse(sent.containsKey("client_secret"))
        }

    @Test fun cimdPreferredAndDeniedEndpointsNeverFallBackToRegistration() {
        val fixture = Fixture()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { fixture.registration.register(metadata(true), REDIRECT) }
        }
        assertTrue(fixture.requests.isEmpty())
        val denied = McpOAuthClientRegistration(McpEndpointGate { error("denied") }, fixture.http)
        assertThrows(IllegalStateException::class.java) { runBlocking { denied.register(metadata(false), REDIRECT) } }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun registrationHttpFailureIsNotRetriedOrReturnedAsIdentity() {
        val fixture = Fixture()
        fixture.status = 503
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { fixture.registration.register(metadata(false), REDIRECT) }
        }
        assertEquals(1, fixture.requests.size)
    }

    @Test fun refreshAndDeviceRequestsCarryOriginalResource() =
        runBlocking {
            val fixture = Fixture()
            val client = McpOAuthClient(fixture.gate, fixture.http)
            fixture.body = """{"access_token":"fixture-token","token_type":"Bearer"}"""
            client.refreshToken("$ISSUER/token", "client", "refresh", RESOURCE)
            client.pollDeviceTokenOnce("$ISSUER/token", "client", "code", RESOURCE)
            fixture.body =
                """{"device_code":"device-fixture","user_code":"CODE","verification_uri":"$ISSUER/verify","expires_in":300,"interval":5}"""
            client.requestDeviceCode("$ISSUER/device", "client", "read", RESOURCE)
            fixture.requests.forEach { request ->
                val form = request.body as FormBody
                val parameters = (0 until form.size).associate { form.name(it) to form.value(it) }
                assertEquals(RESOURCE, parameters["resource"])
            }
        }

    @Test fun discoveryPersistsRequiredIssuerAndCimdFlags() =
        runBlocking {
            val fixture = Fixture()
            fixture.metadataMode = true
            val result = McpOAuthDiscovery(fixture.gate, fixture.http).discover(NormalizedEndpoint.parse(RESOURCE))
            assertTrue(result.authorizationResponseIssParameterSupported)
            assertTrue(result.clientIdMetadataDocumentSupported)
            assertEquals(ISSUER, result.issuer)
        }

    @Test fun metadataFlagsAreStrictAndIssuerIsNotNormalizedIntoAMatch() {
        val fixture = Fixture()
        fixture.metadataMode = true
        listOf(
            metadataDocument().replace(
                "\"authorization_response_iss_parameter_supported\":true",
                "\"authorization_response_iss_parameter_supported\":\"true\"",
            ),
            metadataDocument().replace("\"issuer\":\"$ISSUER\"", "\"issuer\":\"$ISSUER:443\""),
        ).forEach { value ->
            fixture.metadataBody = value
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    McpOAuthDiscovery(
                        fixture.gate,
                        fixture.http,
                    ).discover(NormalizedEndpoint.parse(RESOURCE))
                }
            }
        }
    }

    private class Fixture {
        val requests = CopyOnWriteArrayList<Request>()
        var body = document()
        var status = 200
        var metadataMode = false
        var metadataBody = metadataDocument()
        val gate = McpEndpointGate { McpNetworkPermit(it.host, listOf(byteArrayOf(127, 0, 0, 1))) }
        val http =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    val response =
                        if (!metadataMode) {
                            body
                        } else if (request.url.encodedPath.contains("oauth-protected-resource")) {
                            """{"resource":"$RESOURCE","authorization_servers":["$ISSUER"]}"""
                        } else {
                            metadataBody
                        }
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("fixture")
                        .body(response.toResponseBody())
                        .build()
                }.build()
        val registration = McpOAuthClientRegistration(gate, http)
    }

    private companion object {
        const val ISSUER = "https://issuer.example"
        const val RESOURCE = "https://resource.example/mcp"
        const val DOCUMENT = "https://client.example/oauth/client.json"
        const val REDIRECT = "helix://oauth/mcp/callback"

        fun metadata(cimd: Boolean) =
            McpOAuthServerMetadata(
                ISSUER,
                "$ISSUER/authorize",
                "$ISSUER/token",
                registrationEndpoint = "$ISSUER/register",
                codeChallengeMethodsSupported = listOf("S256"),
                clientIdMetadataDocumentSupported = cimd,
            )

        fun document(id: String = DOCUMENT) =
            """{"client_id":"$id","client_name":"Helix fixture","redirect_uris":["$REDIRECT"],"token_endpoint_auth_method":"none","response_types":["code"],"grant_types":["authorization_code","refresh_token"]}"""

        fun metadataDocument() =
            """{"issuer":"$ISSUER","authorization_endpoint":"$ISSUER/authorize","token_endpoint":"$ISSUER/token","code_challenge_methods_supported":["S256"],"client_id_metadata_document_supported":true,"authorization_response_iss_parameter_supported":true}"""
    }
}
