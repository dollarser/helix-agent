package com.helix.runtime.cli.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliAgentBackendEligibilityTest {
    @Test fun unsupportedRuntimeStopsAtMetadataEvenWhenLaterClaimsAreTrue() {
        val decision = assess(android = false, tools = true, reconciliation = true, authorized = true)

        assertEquals(CliBackendDisposition.METADATA_ONLY_UNSUPPORTED, decision.disposition)
        assertEquals(setOf(CliBackendBlocker.UNSUPPORTED_ANDROID_RUNTIME), decision.blockers)
        assertFalse(decision.mayRegisterForActOrGoal)
    }

    @Test fun runnableRuntimeWithoutToolControlStaysAnIsolatedSession() {
        val decision = assess(android = true, tools = false, reconciliation = true, authorized = true)

        assertEquals(CliBackendDisposition.ISOLATED_CLI_SESSION_ONLY, decision.disposition)
        assertEquals(setOf(CliBackendBlocker.BUILT_IN_TOOL_CONTROL_UNPROVEN), decision.blockers)
        assertFalse(decision.mayRegisterForActOrGoal)
    }

    @Test fun runnableRuntimeWithoutReconciliationStaysAnIsolatedSession() {
        val decision = assess(android = true, tools = true, reconciliation = false, authorized = true)

        assertEquals(CliBackendDisposition.ISOLATED_CLI_SESSION_ONLY, decision.disposition)
        assertEquals(setOf(CliBackendBlocker.JOB_RECONCILIATION_UNPROVEN), decision.blockers)
        assertFalse(decision.mayRegisterForActOrGoal)
    }

    @Test fun allMissingCompatibilityEvidenceIsReportedTogether() {
        val decision =
            assess(
                android = true,
                tools = false,
                reconciliation = false,
                authorized = false,
                channel = CliProviderChannel.CONSUMER_STORE,
            )

        assertEquals(
            setOf(
                CliBackendBlocker.BUILT_IN_TOOL_CONTROL_UNPROVEN,
                CliBackendBlocker.JOB_RECONCILIATION_UNPROVEN,
                CliBackendBlocker.DISTRIBUTION_AUTHORIZATION_UNPROVEN,
            ),
            decision.blockers,
        )
        assertFalse(decision.mayRegisterForActOrGoal)
    }

    @Test fun registrationRequiresEveryIndependentGate() {
        val decision = assess(android = true, tools = true, reconciliation = true, authorized = true)

        assertEquals(CliBackendDisposition.AGENT_BACKEND_ELIGIBLE, decision.disposition)
        assertTrue(decision.blockers.isEmpty())
        assertTrue(decision.mayRegisterForActOrGoal)
    }

    @Test fun storeRegistrationRequiresVendorDistributionAuthorization() {
        val decision =
            assess(
                android = true,
                tools = true,
                reconciliation = true,
                authorized = false,
                channel = CliProviderChannel.CONSUMER_STORE,
            )

        assertEquals(CliBackendDisposition.ISOLATED_CLI_SESSION_ONLY, decision.disposition)
        assertEquals(setOf(CliBackendBlocker.DISTRIBUTION_AUTHORIZATION_UNPROVEN), decision.blockers)
        assertFalse(decision.mayRegisterForActOrGoal)
    }

    @Test fun developerAdvancedRegistrationDoesNotRequireVendorDistributionAuthorization() {
        val decision = assess(android = true, tools = true, reconciliation = true, authorized = false)

        assertEquals(CliBackendDisposition.AGENT_BACKEND_ELIGIBLE, decision.disposition)
        assertTrue(decision.blockers.isEmpty())
        assertTrue(decision.mayRegisterForActOrGoal)
    }

    private fun assess(
        android: Boolean,
        tools: Boolean,
        reconciliation: Boolean,
        authorized: Boolean,
        channel: CliProviderChannel = CliProviderChannel.DEVELOPER_ADVANCED,
    ): CliAgentBackendDecision =
        CliAgentBackendEligibility.assess(
            CliAgentBackendEvidence(
                vendorSupportedAndroidRuntime = android,
                builtInToolsDisabledOrProxied = tools,
                jobIdReconciliationWithoutReplay = reconciliation,
                vendorAuthorizesHelixDistribution = authorized,
            ),
            channel,
        )
}
