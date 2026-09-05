package com.helix.app.provider

import com.helix.core.model.ProviderProtocol
import com.helix.provider.catalog.ProviderTemplateCatalog
import com.helix.runtime.cli.client.CliAgentBackendEligibility
import com.helix.runtime.cli.client.CliAgentBackendEvidence
import com.helix.runtime.cli.client.CliBackendDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CliAgentBackendRegistrationTest {
    @Test fun currentM11EvidenceCannotRegisterAnActOrGoalProvider() {
        val decision =
            CliAgentBackendEligibility.assess(
                CliAgentBackendEvidence(
                    vendorSupportedAndroidRuntime = false,
                    builtInToolsDisabledOrProxied = false,
                    jobIdReconciliationWithoutReplay = false,
                ),
            )

        assertEquals(CliBackendDisposition.METADATA_ONLY_UNSUPPORTED, decision.disposition)
        assertFalse(decision.mayRegisterForActOrGoal)
    }

    @Test fun productionProviderSurfaceContainsOnlyNetworkProtocolsAndNoCliTemplate() {
        assertEquals(
            setOf(
                ProviderProtocol.OPENAI_RESPONSES,
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                ProviderProtocol.ANTHROPIC_MESSAGES,
            ),
            ProviderProtocol.entries.toSet(),
        )
        assertFalse(
            ProviderTemplateCatalog.all.any { template ->
                template.id.contains("codex", ignoreCase = true) ||
                    template.id.contains("claude-code", ignoreCase = true) ||
                    template.id.contains("cli", ignoreCase = true)
            },
        )
    }
}
