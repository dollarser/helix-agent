package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ModelMetadata
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelEvidenceTest {
    private val caps = ProviderCapabilities(true, true, false, false, true, false, 8192, CapabilitySource.PROBED)

    @Test fun catalogRefreshAndModelProbesCannotReplaceUserPreferences() {
        val backing = InMemoryLineStore()
        val choices = ProviderSelectedModels(backing)
        choices.write("p", listOf("b"))
        val evidence = ProviderModelEvidenceStore(backing)
        evidence.catalog("p", "url", listOf("a", "b"))
        evidence.verify("p", "url", "a", ProviderModelVerification(1, caps))
        evidence.catalog("p", "url", listOf("a", "new"))
        val reopened = ProviderModelEvidenceStore(backing).read("p", "url")
        assertEquals(listOf("a", "new"), reopened.catalog)
        assertEquals(caps, reopened.verifications["a"]?.capabilities)
        assertEquals(listOf("b"), choices.read("p"))
    }

    @Test fun exactModelEvidenceCannotBleedAcrossModelsProvidersOrEndpoints() {
        val evidence = ProviderModelEvidenceStore(InMemoryLineStore())
        evidence.verify("p", "one", "b", ProviderModelVerification(1, caps))
        assertNull(evidence.read("p", "one").verifications["a"])
        assertTrue(evidence.read("q", "one").verifications.isEmpty())
        assertTrue(evidence.read("p", "two").verifications.isEmpty())
        evidence.verify("p", "two", "b", ProviderModelVerification(2, failure = ModelErrorCode.AUTH))
        assertTrue(evidence.read("p", "one").verifications.isEmpty())
        assertEquals(ModelErrorCode.AUTH, evidence.read("p", "two").verifications["b"]?.failure)
    }

    @Test fun nondefaultModelHasItsOwnToolAndReasoningEvidenceWithoutChangingDefault() {
        val row = row().copy(modelVerifications = mapOf("b" to ProviderModelVerification(2, caps)))
        assertTrue(requireNotNull(row.capabilitiesForModel("b")).toolCalls)
        assertTrue(ReasoningEffort.HIGH in row.reasoningOptionsFor("b"))
        assertEquals("a", row.model)
        assertNull(row.capabilitiesForModel("unknown"))
        val failed =
            row.copy(
                modelVerifications =
                    mapOf("b" to ProviderModelVerification(3, failure = ModelErrorCode.PROTOCOL)),
            )
        assertNull(failed.capabilitiesForModel("b"))
    }

    @Test fun connectionOnlyEvidenceKeepsDeclaredMetadataButDoesNotInventToolSupport() {
        val row =
            row().copy(
                modelVerifications =
                    mapOf(
                        "b" to
                            ProviderModelVerification(
                                2,
                                caps.copy(
                                    toolCalls = false,
                                    reasoning = false,
                                    source = CapabilitySource.CONNECTION_ONLY,
                                ),
                            ),
                    ),
                modelMetadata = mapOf("b" to ModelMetadata(listOf(ReasoningEffort.HIGH), true, 64000)),
            )
        val actual = requireNotNull(row.capabilitiesForModel("b"))
        assertFalse(actual.toolCalls)
        assertTrue(actual.vision)
        assertEquals(64000L, actual.maxContextTokens)
        assertEquals(listOf(ReasoningEffort.OFF, ReasoningEffort.HIGH), row.reasoningOptionsFor("b"))
    }

    @Test fun explicitVisionDeclarationIsNotShadowedByTheOldExactModelProbe() {
        val row =
            row().copy(
                modelVerifications =
                    mapOf(
                        "a" to
                            ProviderModelVerification(
                                3,
                                caps.copy(vision = true, source = CapabilitySource.MANUAL),
                            ),
                    ),
                modelMetadata = mapOf("a" to ModelMetadata(null, false, 8192)),
            )
        assertTrue(requireNotNull(row.capabilitiesForModel("a")).vision)
        assertEquals(CapabilitySource.MANUAL, row.capabilitiesForModel("a")?.source)
        assertNull(row.capabilitiesForModel("b"))
    }

    @Test fun malformedEvidenceNeverBecomesSuccessfulCapabilities() {
        val backing = InMemoryLineStore()
        val store = ProviderModelEvidenceStore(backing)
        listOf(
            "{broken",
            """{"identity":"one","catalog":[1],"tests":{}}""",
            """{"identity":"one","catalog":null,"tests":{"bad key":{}}}""",
        ).forEach { text ->
            backing.setLines("provider-model-evidence-v1-p", listOf(text))
            assertEquals(ProviderModelEvidence(), store.read("p", "one"))
        }
    }

    @Test fun displayRenameIsNotAConnectionChangeButKeyProtocolEndpointAndModelAre() {
        val config =
            ProviderConfig.fromStorage(
                "p",
                "Source",
                "OPENAI_CHAT_COMPLETIONS",
                "https://example.test/v1",
                "a",
                "{}",
                null,
                ProviderCapabilities.toJsonString(caps),
                authKind = "NONE",
            )
        val draft =
            ProviderDraft(null, "Renamed", config.protocol, config.endpoint, "a", "{}", false, null, emptyList())
        assertFalse(providerConnectionChanged(config, draft, null))
        assertTrue(providerConnectionChanged(config, draft, "synthetic-key"))
        assertTrue(providerConnectionChanged(config, draft.copy(model = "b"), null))
        assertTrue(providerConnectionChanged(config, draft.copy(protocol = ProviderProtocol.OPENAI_RESPONSES), null))
        assertTrue(
            providerConnectionChanged(
                config,
                draft.copy(endpoint = NormalizedEndpoint.parse("https://other.test/v1")),
                null,
            ),
        )
    }

    private fun row() =
        ProviderRowUi(
            "p",
            "Source",
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            "https://example.test",
            ProviderResidence.PUBLIC_CLOUD,
            "a",
            false,
            false,
            ConnectionTestStatus.Passed(1, caps, listOf("a", "b")),
            caps,
            listOf("a", "b"),
            emptyList(),
        )
}
