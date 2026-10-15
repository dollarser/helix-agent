package com.helix.app.marketplace

import com.helix.extensions.plugin.PluginPackageReader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceCatalogTest {
    @Test
    fun catalogItemsAreWellFormedAndUnique() {
        val items = MarketplaceCatalog.items()
        assertTrue("Catalog must provide reusable entries", items.isNotEmpty())

        val ids = items.map { it.id }
        org.junit.Assert.assertEquals("IDs must be unique", ids.distinct().size, ids.size)

        val reader = PluginPackageReader()

        for (item in items) {
            assertTrue("ID must be valid", item.id.isNotBlank())
            assertTrue("Name res must be valid", item.nameRes != 0)
            assertTrue("Summary res must be valid", item.summaryRes != 0)
            assertTrue("Description res must be valid", item.descriptionRes != 0)
            assertTrue("Author must be valid", item.author.isNotBlank())
            assertTrue("Tags must not be empty", item.tags.isNotEmpty())
            assertNotNull("Target connector name must be set", item.targetConnectorName)

            when (item.type) {
                MarketplaceItemType.CONNECTOR, MarketplaceItemType.MCP -> {
                    val bundle = reader.readJson(item.payload.toByteArray(Charsets.UTF_8))
                    assertNotNull("Bundle should be parsed", bundle)
                    assertTrue("Bundle should have endpoints", bundle.endpoints.isNotEmpty())
                    bundle.endpoints.forEach { endpoint ->
                        val host = java.net.URI(endpoint.url).host
                        assertFalse("Curated endpoints must not be placeholders", host.endsWith(".local"))
                        assertFalse("Curated endpoints must not be placeholders", host.endsWith(".invalid"))
                    }
                    if (item.authRequirement == MarketplaceAuthRequirement.NONE) {
                        assertFalse(
                            "Public endpoint should not require credentials",
                            bundle.endpoints.single().needsCredential,
                        )
                    } else if (item.authRequirement == MarketplaceAuthRequirement.BEARER_TOKEN ||
                        item.authRequirement == MarketplaceAuthRequirement.API_KEY ||
                        item.authRequirement == MarketplaceAuthRequirement.OAUTH
                    ) {
                        assertTrue(
                            "Protected endpoint must require credentials",
                            bundle.endpoints.single().needsCredential,
                        )
                    }
                }

                MarketplaceItemType.SKILL -> {
                    assertTrue("Skill payload must contain frontmatter", item.payload.contains("---"))
                    assertTrue("Skill payload must have name", item.payload.contains("name:"))
                    assertTrue("Skill payload must have description", item.payload.contains("description:"))
                }
            }
        }
    }

    @Test fun officialOAuthEntriesRemainProtectedAndDoNotEmbedClientCredentials() {
        val endpoints =
            mapOf(
                "notion-workspace" to "https://mcp.notion.com/mcp",
                "linear-workspace" to "https://mcp.linear.app/mcp",
                "gitlab-workspace" to "https://gitlab.com/api/v4/mcp",
            )
        for ((id, url) in endpoints) {
            val item = MarketplaceCatalog.items().single { it.id == id }
            org.junit.Assert.assertEquals(MarketplaceAuthRequirement.OAUTH, item.authRequirement)
            val bundle = PluginPackageReader().readJson(item.payload.toByteArray())
            val endpoint = bundle.endpoints.single()
            org.junit.Assert.assertEquals(url, endpoint.url)
            assertTrue("Installation must still require authorization", endpoint.needsCredential)
            assertFalse(item.payload.contains("client_secret"))
            assertFalse(item.payload.contains("access_token"))
            assertTrue(bundle.skills.isEmpty())
        }
    }
}
