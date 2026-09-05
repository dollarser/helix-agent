package com.helix.runtime.cli.client

/**
 * Closed HXA-113 gate between an official CLI companion and Helix Act/Goal providers.
 *
 * A runnable CLI is not sufficient: its built-in effects must be disabled or represented as
 * ordinary Helix ToolCalls, an interrupted job must be queryable by its original jobId without
 * replay. Consumer/store registration also requires verifiable vendor authorization; an explicitly
 * unofficial developer/Advanced sideload does not. Protocol compatibility, a public client id, or
 * a successful personal smoke cannot substitute for store authorization. The caller supplies
 * independently verified evidence for each gate; absence is a rejection where that gate applies.
 */
object CliAgentBackendEligibility {
    fun assess(
        evidence: CliAgentBackendEvidence,
        channel: CliProviderChannel,
    ): CliAgentBackendDecision =
        when {
            !evidence.vendorSupportedAndroidRuntime -> {
                CliAgentBackendDecision(
                    disposition = CliBackendDisposition.METADATA_ONLY_UNSUPPORTED,
                    blockers = setOf(CliBackendBlocker.UNSUPPORTED_ANDROID_RUNTIME),
                )
            }

            else -> {
                val blockers =
                    buildSet {
                        if (!evidence.builtInToolsDisabledOrProxied) {
                            add(CliBackendBlocker.BUILT_IN_TOOL_CONTROL_UNPROVEN)
                        }
                        if (!evidence.jobIdReconciliationWithoutReplay) {
                            add(CliBackendBlocker.JOB_RECONCILIATION_UNPROVEN)
                        }
                        if (channel == CliProviderChannel.CONSUMER_STORE &&
                            !evidence.vendorAuthorizesHelixDistribution
                        ) {
                            add(CliBackendBlocker.DISTRIBUTION_AUTHORIZATION_UNPROVEN)
                        }
                    }
                CliAgentBackendDecision(
                    disposition =
                        if (blockers.isEmpty()) {
                            CliBackendDisposition.AGENT_BACKEND_ELIGIBLE
                        } else {
                            CliBackendDisposition.ISOLATED_CLI_SESSION_ONLY
                        },
                    blockers = blockers,
                )
            }
        }
}

data class CliAgentBackendEvidence(
    val vendorSupportedAndroidRuntime: Boolean,
    val builtInToolsDisabledOrProxied: Boolean,
    val jobIdReconciliationWithoutReplay: Boolean,
    val vendorAuthorizesHelixDistribution: Boolean,
)

data class CliAgentBackendDecision(
    val disposition: CliBackendDisposition,
    val blockers: Set<CliBackendBlocker>,
) {
    val mayRegisterForActOrGoal: Boolean
        get() = disposition == CliBackendDisposition.AGENT_BACKEND_ELIGIBLE && blockers.isEmpty()
}

enum class CliProviderChannel {
    DEVELOPER_ADVANCED,
    CONSUMER_STORE,
}

enum class CliBackendDisposition {
    METADATA_ONLY_UNSUPPORTED,
    ISOLATED_CLI_SESSION_ONLY,
    AGENT_BACKEND_ELIGIBLE,
}

enum class CliBackendBlocker {
    UNSUPPORTED_ANDROID_RUNTIME,
    BUILT_IN_TOOL_CONTROL_UNPROVEN,
    JOB_RECONCILIATION_UNPROVEN,
    DISTRIBUTION_AUTHORIZATION_UNPROVEN,
}
