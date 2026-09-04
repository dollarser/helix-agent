package com.helix.extensions.a2a

import com.helix.core.model.A2aAgentId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SecretAlias
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A2aDiscoveryServiceTest {
    @Test
    fun `public Card selects first supported v1 interface and bounds the snapshot`() =
        runBlocking {
            val fake = FakeCardClient(publicCard = card(extended = false))
            val service = A2aDiscoveryService(fake, A2aCredentialLookup { error("credential must not be read") })

            val snapshot = service.discover(config())

            assertEquals(A2aBinding.JSON_RPC, snapshot.selectedInterface.binding)
            assertEquals("1.0", snapshot.selectedInterface.protocolVersion)
            assertEquals("tenant-1", snapshot.selectedInterface.tenant)
            assertEquals("Example Corp", snapshot.provider?.organization)
            assertEquals(listOf("text/plain"), snapshot.defaultInputModes)
            assertEquals(listOf("echo"), snapshot.skills.map { it.id })
            assertEquals(64, snapshot.cardHash.hex.length)
            assertFalse(snapshot.extended)
            assertEquals(1, fake.publicCalls)
            assertEquals(0, fake.extendedCalls)
        }

    @Test
    fun `extended Card is fetched only with the configured Secret alias`() =
        runBlocking {
            val fake =
                FakeCardClient(
                    publicCard = card(extended = true),
                    extendedCard = card(extended = true, skillId = "private"),
                )
            val seenAliases = mutableListOf<SecretAlias>()
            val service =
                A2aDiscoveryService(
                    fake,
                    A2aCredentialLookup { alias ->
                        seenAliases += alias
                        "secret-token"
                    },
                )

            val snapshot = service.discover(config(authAlias = SecretAlias("a2a-example")))

            assertTrue(snapshot.extended)
            assertEquals(listOf("private"), snapshot.skills.map { it.id })
            assertEquals(listOf(SecretAlias("a2a-example")), seenAliases)
            assertEquals(listOf("secret-token"), fake.bearers)
        }

    @Test
    fun `Card changes alter hashes while key order and whitespace do not`() =
        runBlocking {
            val service = A2aDiscoveryService(FakeCardClient(card()), A2aCredentialLookup { error("unused") })
            val first = service.discover(config())
            val reordered =
                card().replace(
                    "\"name\":\"Example Agent\",\n          \"description\":\"A fixture\"",
                    "\"description\": \"A fixture\", \"name\": \"Example Agent\"",
                )
            val same =
                A2aDiscoveryService(
                    FakeCardClient(reordered),
                    A2aCredentialLookup { error("unused") },
                ).discover(config())
            val changed =
                A2aDiscoveryService(
                    FakeCardClient(card(skillId = "changed")),
                    A2aCredentialLookup {
                        error("unused")
                    },
                ).discover(config())

            assertEquals(first.cardHash, same.cardHash)
            assertEquals(first.skills.single().contentHash, same.skills.single().contentHash)
            assertFalse(first.cardHash == changed.cardHash)
            assertFalse(first.skills.single().contentHash == changed.skills.single().contentHash)
        }

    @Test
    fun `unsupported versions origins required extensions duplicate Skills and oversized Cards fail closed`() =
        runBlocking {
            val invalidCards =
                listOf(
                    card().replace("\"protocolVersion\":\"1.0\"", "\"protocolVersion\":\"0.3\""),
                    card().replace("https://agent.example/a2a", "https://other.example/a2a"),
                    card().replace(
                        "\"extendedAgentCard\":false",
                        "\"extendedAgentCard\":false," +
                            "\"extensions\":[{\"uri\":\"https://example/ext\",\"required\":true}]",
                    ),
                    card().replace("\"skills\":[", "\"skills\":[${skill("echo")},"),
                    card().replace("\"description\":\"A fixture\"", "\"description\":\"${"x".repeat(513 * 1024)}\""),
                )
            invalidCards.forEachIndexed { index, raw ->
                assertFails("invalid Card case $index") {
                    A2aDiscoveryService(FakeCardClient(raw), A2aCredentialLookup { "unused" }).discover(config())
                }
            }
        }

    private fun config(authAlias: SecretAlias? = null): A2aAgentConfig =
        A2aAgentConfig.disabled(
            A2aAgentId("example-agent"),
            NormalizedEndpoint.parse("https://agent.example/.well-known/agent-card.json"),
            authAlias,
        )

    private fun card(
        extended: Boolean = false,
        skillId: String = "echo",
    ): String =
        """
        {
          "name":"Example Agent",
          "description":"A fixture",
          "supportedInterfaces":[
            {"url":"https://agent.example/grpc","protocolBinding":"GRPC","protocolVersion":"1.0"},
            {"url":"https://agent.example/legacy","protocolBinding":"JSONRPC","protocolVersion":"0.3"},
            {"url":"https://agent.example/a2a","protocolBinding":"JSONRPC","protocolVersion":"1.0","tenant":"tenant-1"},
            {"url":"https://agent.example/rest","protocolBinding":"HTTP+JSON","protocolVersion":"1.0"}
          ],
          "provider":{"organization":"Example Corp","url":"https://example.com"},
          "version":"2.0.0",
          "capabilities":{"streaming":true,"pushNotifications":false,"extendedAgentCard":$extended},
          "defaultInputModes":["text/plain"],
          "defaultOutputModes":["text/plain","application/json"],
          "skills":[${skill(skillId)}]
        }
        """.trimIndent()

    private fun skill(id: String): String =
        """{"id":"$id","name":"Echo","description":"Echo bounded text","tags":["fixture"]}"""

    private suspend fun assertFails(
        message: String = "expected discovery failure",
        block: suspend () -> Unit,
    ) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(message, failed)
    }

    private class FakeCardClient(
        private val publicCard: String,
        private val extendedCard: String = publicCard,
    ) : A2aAgentCardClient {
        var publicCalls: Int = 0
        var extendedCalls: Int = 0
        val bearers = mutableListOf<String>()

        override suspend fun fetchPublic(cardEndpoint: NormalizedEndpoint): String {
            publicCalls += 1
            return publicCard
        }

        override suspend fun fetchExtended(
            selectedInterface: A2aInterfaceSnapshot,
            bearer: String,
        ): String {
            extendedCalls += 1
            bearers += bearer
            return extendedCard
        }
    }
}
