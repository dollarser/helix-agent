package com.helix.runtime.cli.app

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CodexSmokeCatalogTest {
    @Test fun visibleModelIsSelectedInCatalogOrder() {
        assertEquals("first", read("""{"models":[{"slug":"first"},{"slug":"second"}]}"""))
    }

    @Test fun hiddenAndBlankModelsAreSkipped() {
        val body = """{"models":[{"slug":"hidden","visibility":"hide"},{"slug":""},{"slug":"visible"}]}"""
        assertEquals("visible", read(body))
    }

    @Test fun emptyCatalogHasTypedFailure() {
        assertEquals("models-empty", failure("""{"models":[]}""").stage)
    }

    @Test fun nonObjectRowHasProtocolFailure() {
        assertEquals("models-protocol", failure("""{"models":[42]}""").stage)
    }

    @Test fun numericSlugIsNotAModelIdentifier() {
        assertEquals("models-protocol", failure("""{"models":[{"slug":42}]}""").stage)
    }

    @Test fun malformedCatalogHasProtocolFailure() {
        assertEquals("models-protocol", failure("""{"models":{}}""").stage)
        assertEquals("models-protocol", failure("{broken").stage)
    }

    @Test fun oversizedCatalogHasSizeFailure() {
        assertEquals(
            "models-too-large",
            failure(" ".repeat(CodexSubscriptionSmoke.MAX_CATALOG_BYTES.toInt() + 1)).stage,
        )
    }

    @Test fun exactCatalogLimitRemainsValid() {
        val body = """{"models":[{"slug":"model"}]}"""
        assertEquals("model", read(body + " ".repeat(CodexSubscriptionSmoke.MAX_CATALOG_BYTES.toInt() - body.length)))
    }

    private fun read(body: String): String = CodexSmokeCatalog.read(Buffer().writeUtf8(body))

    private fun failure(body: String): CodexSmokeException =
        assertThrows(CodexSmokeException::class.java) { read(body) }
}
