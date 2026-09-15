package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.runtime.cli.client.CliModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexModelCatalogTest {
    @Test fun serverDeclaredFutureModelAndEffortRemainDiscoverable() {
        val catalog =
            CodexModelCatalog.parse(
                """{"models":[
          {"slug":"future","supported_reasoning_levels":[{"effort":"adaptive_next"}],
           "input_modalities":["text","image"],"context_window":300001},
          {"slug":"unknown"},{"slug":"hidden","visibility":"hide"}
        ]}""".toByteArray(),
            )
        assertEquals(listOf("future", "unknown"), catalog.models.map { it.id })
        assertEquals(listOf("adaptive_next"), catalog.models.first().reasoningEfforts)
        assertEquals(true, catalog.models.first().vision)
        assertEquals(300001L, catalog.models.first().contextWindow)
        assertNull(catalog.models.last().reasoningEfforts)
        assertNull(catalog.models.last().vision)
    }

    @Test fun malformedCatalogIsAProtocolArgumentFailure() {
        assertThrows(IllegalArgumentException::class.java) { CodexModelCatalog.parse("{}".toByteArray()) }
    }

    @Test fun loggedOutCatalogIsAStableAuthFailureWithoutRefreshTraffic() {
        val vault = CliSubscriptionCredentialVault(MemorySecretStore())
        val transport =
            object : CodexOAuthTransport {
                override fun exchange(
                    attempt: CodexOAuthAttempt,
                    code: String,
                ): CliSubscriptionSession = error("no login in this test")

                override fun refresh(session: CliSubscriptionSession): CliSubscriptionSession =
                    error("logged-out catalog must not refresh")
            }
        val catalog = CodexModelCatalog(vault, CodexLoginController(vault, transport)).fetch()
        assertTrue(catalog is CliModelCatalog.Failed)
        assertEquals(ModelErrorCode.AUTH, (catalog as CliModelCatalog.Failed).code)
        assertEquals(false, catalog.retryable)
    }

    private class MemorySecretStore : CliSecretStore {
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
