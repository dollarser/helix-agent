package com.helix.app.marketplace

import com.helix.extensions.skills.connector.ConnectorPackageReader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceCatalogTest {
    @Test
    fun catalogItemsAreWellFormedAndUnique() {
        val items = MarketplaceCatalog.items()
        assertTrue("Catalog should not be empty", items.isNotEmpty())

        val ids = items.map { it.id }
        org.junit.Assert.assertEquals("IDs must be unique", ids.distinct().size, ids.size)

        val reader = ConnectorPackageReader()

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
                    if (item.authRequirement == MarketplaceAuthRequirement.NONE) {
                        assertFalse(
                            "Public endpoint should not require credentials",
                            bundle.endpoints.single().needsCredential,
                        )
                    } else if (item.authRequirement == MarketplaceAuthRequirement.BEARER_TOKEN ||
                        item.authRequirement == MarketplaceAuthRequirement.API_KEY
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
}
